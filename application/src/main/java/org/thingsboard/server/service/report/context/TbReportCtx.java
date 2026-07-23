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
     * How many {@code SUB_REPORT} levels deep this render is (0 = the top-level report; {@link
     * #createSubReportCxt} sets each child to parent + 1). {@code PdfReportService} caps this at its
     * {@code MAX_SUB_REPORT_DEPTH} before recursing, so a self-referential template ({@code templateId} =
     * its own id), a cycle (A→B→A) or pathologically deep nesting cannot recurse without bound — an Inferrix
     * hardening over PE, which has no such cap and recurses until {@code StackOverflowError}. Defaults to 0.
     */
    @Builder.Default
    private final int subReportDepth = 0;

    /** Per-render, mutable scratch space for the data layer (e.g. the current sub-report entity). Never builder-set — always fresh. */
    @Builder.Default
    private final Map<String, Object> params = new HashMap<>();
    /** Per-render TBEL script cache (data-key post-func body → compiled script id), released by {@link #close()}. Never builder-set. */
    @Builder.Default
    private final Map<String, UUID> scripts = new HashMap<>();

    /**
     * Builds a child context for a {@code SUB_REPORT} component: same identity/security scope (tenant, user,
     * owner, token, {@link SecurityUser}, image resolver, TBEL service) but rendering a different (child)
     * {@link ReportTemplateConfig}, with its own fresh {@code params}/{@code scripts}. The child inherits this
     * ctx's {@link SecurityUser}, so a sub-report can never read outside the parent report's permission scope.
     * Its {@link #subReportDepth} is this ctx's + 1, so {@code PdfReportService}'s {@code MAX_SUB_REPORT_DEPTH}
     * cap bounds recursion (self-reference / cycle / pathologically deep nesting) — R2b task H renders through
     * this seam.
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
