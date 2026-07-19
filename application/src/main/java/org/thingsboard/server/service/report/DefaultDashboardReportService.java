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
package org.thingsboard.server.service.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.service.report.render.PlaywrightWebReportRenderer;
import org.thingsboard.server.service.report.render.RenderOptions;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.UUID;

/**
 * On-demand / test dashboard-report entry point (design spec §6.1). Mints a password-less access
 * token for the report's configured user, resolves a {@link RenderOptions} from the request config,
 * and hands both to {@link PlaywrightWebReportRenderer#renderDashboard} — the same renderer bean
 * Task 11 built and Task 13 taught the PE {@code openReport} handshake.
 * <p>
 * Guarded by the same {@code reports.renderer.enabled} property as
 * {@link PlaywrightWebReportRenderer} itself (that bean is disabled by default until Task 26 wires
 * real config): without this guard, Spring would fail to start any environment that hasn't opted
 * into the renderer, since this service's constructor hard-depends on it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class DefaultDashboardReportService implements DashboardReportService {

    private final PlaywrightWebReportRenderer renderer;
    private final SystemSecurityService systemSecurityService;

    @Override
    public ReportData generateReport(TenantId tenantId, DashboardReportConfig config) {
        String accessToken = mintAccessToken(tenantId, config);
        RenderOptions options = buildRenderOptions(config);
        ReportData reportData = renderer.renderDashboard(config.getBaseUrl(), accessToken, options);
        log.info("Generated dashboard report [{}] for tenant [{}], dashboard [{}], {} bytes",
                reportData.getName(), tenantId, config.getDashboardId(),
                reportData.getData() != null ? reportData.getData().length : 0);
        return reportData;
    }

    /**
     * PE-verbatim mint call ({@code DefaultDashboardReportService.generateReport}, decompiled from
     * {@code report-4.2.0PE.jar}): always {@link SystemSecurityService#createUserAccessToken}
     * against {@code config.getUserId()}. PE's public-ID mint variant
     * ({@code createUserAccessTokenFromPublicId}) belongs to a *different* PE method
     * ({@code generateDashboardReport}, the "download the dashboard I'm currently viewing"
     * controller path that resolves publicId off the caller's own {@code SecurityUser}) — out of
     * this task's scope, and {@link DashboardReportConfig} carries no {@code publicId} field to
     * branch on here regardless (verified against PE's decompiled DTO bytecode: {@code baseUrl,
     * dashboardId, state, timezone, useDashboardTimewindow, timewindow, namePattern, type,
     * useCurrentUserCredentials, userId} — no more, no less).
     * <p>
     * Carries Task 10's finding forward: an authority-less user fails token mint with
     * {@link IllegalArgumentException} (thrown by {@code JwtTokenFactory.createAccessJwtToken}),
     * not an {@code AuthenticationException}. Logged here for operator context and re-thrown
     * unmodified — not swallowed, not masked as a
     * {@link org.thingsboard.server.service.report.render.ReportRenderException} — so a later
     * controller layer can still catch it by its real type and map it to a clean 4xx instead of an
     * opaque 500.
     */
    private String mintAccessToken(TenantId tenantId, DashboardReportConfig config) {
        try {
            UserId userId = new UserId(UUID.fromString(config.getUserId()));
            return systemSecurityService.createUserAccessToken(tenantId, userId);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to mint dashboard report access token, tenant [{}], dashboard [{}], userId [{}]: {}",
                    tenantId, config.getDashboardId(), config.getUserId(), e.getMessage());
            throw e;
        }
    }

    /**
     * {@code pageWidth} is intentionally left unset: {@link DashboardReportConfig} (the on-demand /
     * test-report body) doesn't carry one — only the R2 templated-report path's
     * {@code DashboardComponent} does (spec §2.4). The renderer falls back to the page's current
     * viewport width when {@code pageWidth} is null.
     */
    private static RenderOptions buildRenderOptions(DashboardReportConfig config) {
        return RenderOptions.builder()
                .dashboardId(config.getDashboardId())
                .state(config.getState())
                .timewindow(config.isUseDashboardTimewindow() ? null : config.getTimewindow())
                .type(config.getType())
                .namePattern(config.getNamePattern())
                .timezone(config.getTimezone())
                .build();
    }

}
