/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.report.context;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.thingsboard.script.api.tbel.TbelInvokeService;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-render context handed to every {@link org.thingsboard.server.service.report.TbReportRenderService}
 * and, in R2b, to every {@link org.thingsboard.server.service.report.datasource.ReportDataService} query
 * (PE names this {@code TbReportCtx}, {@code report.context}). Carries everything one {@link ReportTask}'s
 * render needs: the tenant scope, the typed {@link ReportTemplateConfig}, the render timezone/created-time,
 * the report user (+ owner), that user's minted access token, the reconstructed trusted {@link
 * SecurityUser}, the {@link PdfReportImageResolver} the flying-saucer user agent fetches images through, and
 * (R2b data layer) the {@link TbelInvokeService} + a per-render {@code scripts}/{@code params} scratch space.
 * <p>
 * <b>Security.</b> {@link #securityUser} is the trust anchor for the whole render: {@code
 * ReportDataService} reads it from here (never from the query/URI) so every entity/alarm/timeseries read is
 * permission-scoped to the report task's user. {@code accessToken} and {@code securityUser} are
 * {@code @ToString.Exclude}d so a stray {@code log.debug(ctx)} can't leak the token or credentials.
 * <p>
 * <b>Closeable.</b> The R2b data layer compiles per-render TBEL post-processing scripts into {@link
 * #scripts}; {@link #close()} releases them from the {@link TbelInvokeService}. The render dispatcher
 * ({@code TbReportService}) closes the ctx after each render. In R2b-F1 nothing populates {@code scripts}
 * yet, so {@code close()} is a no-op until the post-processing layer (F2) lands.
 */
@Getter
@Builder
@ToString
public class TbReportCtx implements Closeable {

    private final TenantId tenantId;
    private final ReportTemplateConfig configuration;
    private final String timeZone;
    private final UserId userId;
    private final EntityId userOwnerId;
    @ToString.Exclude
    private final String accessToken;
    private final long accessTokenExpTs;
    private final String reportCreatedTime;
    private final PdfReportImageResolver imageResolver;
    @ToString.Exclude
    private final SecurityUser securityUser;
    private final TbelInvokeService tbelInvokeService;

    /**
     * Maximum {@code SUB_REPORT} nesting depth. <b>Inferrix hardening over PE, which has NO bound:</b> PE's
     * {@code renderSubReport} → {@code renderContent} → (nested SUB_REPORT) → {@code renderSubReport} recurses
     * forever on a self-referential template ({@code templateId} = its own id) or a cycle (A→B→A) — {@code
     * StackOverflowError} plus per-level amplified DB reads = a DoS. Lives here (not private to one engine) so
     * BOTH render engines — {@code PdfReportService} and {@code CsvReportService} — bound their sub-report
     * recursion against the SAME constant + the SAME {@link #isSubReportDepthExceeded()} check and can't
     * diverge. {@link #subReportDepth} increments once per {@link #createSubReportCxt}; each engine refuses to
     * recurse at this cap, so the one check bounds self-reference, cycles AND pathologically deep nesting.
     */
    public static final int MAX_SUB_REPORT_DEPTH = 10;

    /**
     * How many {@code SUB_REPORT} levels deep this render is (0 = the top-level report; {@link
     * #createSubReportCxt} sets each child to parent + 1). Both render engines cap this at {@link
     * #MAX_SUB_REPORT_DEPTH} (via {@link #isSubReportDepthExceeded()}) before recursing, so a self-referential
     * template ({@code templateId} = its own id), a cycle (A→B→A) or pathologically deep nesting cannot recurse
     * without bound — an Inferrix hardening over PE, which has no such cap and recurses until {@code
     * StackOverflowError}. Defaults to 0.
     */
    @Builder.Default
    private final int subReportDepth = 0;

    /**
     * Shared, per-report budget of total {@code SUB_REPORT} renders across the WHOLE tree — header, footer,
     * body, and every fan-out branch. Where {@link #subReportDepth} bounds only nesting DEPTH (a per-branch
     * counter), this bounds total WORK: a sub-report resolves E entities and renders its child once per entity,
     * so the render tree is E-ary and E^depth total renders would exhaust the shared render heap (a cross-tenant
     * OOM) even within the depth cap. Threaded BY REFERENCE through {@link #createSubReportCxt} — every child ctx
     * shares the SAME instance — and consumed once per {@code renderSubReport} node by BOTH engines via {@link
     * #consumeSubReportBudget()}; when it goes negative that node degrades to an error instead of descending.
     * Fresh per top-level render ({@code @Builder.Default}) so it can't leak across reports. Inferrix hardening
     * over PE (PE bounds neither depth nor fan-out). One render thread, so a plain {@link AtomicInteger} suffices.
     */
    public static final int MAX_TOTAL_SUB_REPORT_RENDERS = 1000;
    @Builder.Default
    private final AtomicInteger subReportBudget = new AtomicInteger(MAX_TOTAL_SUB_REPORT_RENDERS);

    /** Per-render, mutable scratch space for the data layer (e.g. the current sub-report entity). Never builder-set — always fresh. */
    @Builder.Default
    private final Map<String, Object> params = new HashMap<>();
    /** Per-render TBEL script cache (data-key post-func body → compiled script id), released by {@link #close()}. Never builder-set. */
    @Builder.Default
    private final Map<String, UUID> scripts = new HashMap<>();

    /**
     * Whether this render is already at/over the maximum sub-report nesting depth ({@link
     * #MAX_SUB_REPORT_DEPTH}). Both {@code PdfReportService} and {@code CsvReportService} check this before
     * recursing into a {@code SUB_REPORT}, so a self-referential template / cycle / pathologically deep nesting
     * cannot recurse without bound. Pure query — no side effect.
     */
    public boolean isSubReportDepthExceeded() {
        return subReportDepth >= MAX_SUB_REPORT_DEPTH;
    }

    /**
     * Consumes one unit of the shared {@link #subReportBudget} (decrement) and reports whether a render slot
     * remained. <b>Side-effecting</b> — call EXACTLY ONCE per {@code renderSubReport} node, and BEFORE {@code
     * findReportTemplate}, so the budget also bounds the amplified DB reads. Returns {@code true} to proceed
     * with the render, {@code false} once the budget is exhausted (the caller must degrade to an error instead
     * of descending). Both render engines route through this one method so their fan-out bound can't diverge.
     */
    public boolean consumeSubReportBudget() {
        return subReportBudget.decrementAndGet() >= 0;
    }

    /**
     * Builds a child context for a {@code SUB_REPORT} component: same identity/security scope (tenant, user,
     * owner, token, {@link SecurityUser}, image resolver, TBEL service) but rendering a different (child)
     * {@link ReportTemplateConfig}, with its own fresh {@code params}/{@code scripts}. The child inherits this
     * ctx's {@link SecurityUser}, so a sub-report can never read outside the parent report's permission scope.
     * Its {@link #subReportDepth} is this ctx's + 1 (so {@code MAX_SUB_REPORT_DEPTH} bounds nesting depth) and it
     * shares this ctx's {@link #subReportBudget} instance (so {@code MAX_TOTAL_SUB_REPORT_RENDERS} bounds total
     * fan-out) — together they stop self-reference / cycles / deep nesting / E^depth fan-out. R2b task H renders
     * through this seam.
     */
    public TbReportCtx createSubReportCxt(ReportTemplateConfig subConfiguration) {
        return TbReportCtx.builder()
                .tenantId(tenantId)
                .configuration(subConfiguration)
                .timeZone(timeZone)
                .userId(userId)
                .userOwnerId(userOwnerId)
                .accessToken(accessToken)
                .accessTokenExpTs(accessTokenExpTs)
                .reportCreatedTime(reportCreatedTime)
                .imageResolver(imageResolver)
                .securityUser(securityUser)
                .tbelInvokeService(tbelInvokeService)
                .subReportDepth(subReportDepth + 1)
                // Share the SAME budget instance (do NOT let @Builder.Default mint a fresh one) so total renders
                // are capped across the whole tree, not reset per branch — the fan-out (E^depth) bound.
                .subReportBudget(subReportBudget)
                .build();
    }

    /**
     * Releases every per-render TBEL script compiled into {@link #scripts}. Narrows {@link Closeable#close()}
     * to throw nothing so callers can use try-with-resources without an {@code IOException} clause. Null-safe:
     * contexts built without a {@link TbelInvokeService} (unit-test/render-only ctxs) have no scripts to free.
     */
    @Override
    public void close() {
        if (tbelInvokeService == null || scripts == null || scripts.isEmpty()) {
            return;
        }
        scripts.values().forEach(scriptId -> {
            if (scriptId != null) {
                tbelInvokeService.release(scriptId);
            }
        });
    }

}
