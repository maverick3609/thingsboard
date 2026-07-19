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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.service.report.render.PlaywrightWebReportRenderer;
import org.thingsboard.server.service.report.render.RenderOptions;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers Task 13: {@link DefaultDashboardReportService} mints the report user's token, resolves a
 * {@link RenderOptions} from the request {@link DashboardReportConfig}, and delegates to
 * {@link PlaywrightWebReportRenderer#renderDashboard} — both collaborators mocked, no real
 * Playwright/browser involved (that's {@link DashboardRenderIntegrationTest}'s job).
 */
@ExtendWith(MockitoExtension.class)
class DefaultDashboardReportServiceTest {

    @Mock
    private PlaywrightWebReportRenderer renderer;
    @Mock
    private SystemSecurityService systemSecurityService;

    private DefaultDashboardReportService service;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new DefaultDashboardReportService(renderer, systemSecurityService);
    }

    @Test
    void generateReportMintsTokenBuildsRenderOptionsAndReturnsRendererResult() {
        JsonNode timewindow = JacksonUtil.newObjectNode().put("displayValue", "");
        DashboardReportConfig config = new DashboardReportConfig();
        config.setBaseUrl("https://cortex.inferrix.com");
        config.setDashboardId("11111111-1111-1111-1111-111111111111");
        config.setState("state-blob");
        config.setTimezone("Europe/Kiev");
        config.setUseDashboardTimewindow(false);
        config.setTimewindow(timewindow);
        config.setNamePattern("report-%d{yyyy-MM-dd}");
        config.setType("pdf");
        config.setUserId(userId.toString());

        when(systemSecurityService.createUserAccessToken(tenantId, userId)).thenReturn("minted-jwt");
        ReportData rendered = ReportData.builder()
                .data(new byte[]{1, 2, 3}).name("report-2026-07-19.pdf").contentType("application/pdf").build();
        when(renderer.renderDashboard(eq("https://cortex.inferrix.com"), eq("minted-jwt"), any(RenderOptions.class)))
                .thenReturn(rendered);

        ReportData result = service.generateReport(tenantId, config);

        assertThat(result).isSameAs(rendered);
        verify(systemSecurityService).createUserAccessToken(tenantId, userId);

        ArgumentCaptor<RenderOptions> optionsCaptor = ArgumentCaptor.forClass(RenderOptions.class);
        verify(renderer).renderDashboard(eq("https://cortex.inferrix.com"), eq("minted-jwt"), optionsCaptor.capture());
        RenderOptions options = optionsCaptor.getValue();
        assertThat(options.getDashboardId()).isEqualTo(config.getDashboardId());
        assertThat(options.getState()).isEqualTo("state-blob");
        assertThat(options.getTimewindow()).isEqualTo(timewindow);
        assertThat(options.getType()).isEqualTo("pdf");
        assertThat(options.getNamePattern()).isEqualTo("report-%d{yyyy-MM-dd}");
        assertThat(options.getTimezone()).isEqualTo("Europe/Kiev");
    }

    @Test
    void generateReportOmitsTimewindowWhenUseDashboardTimewindowIsTrue() {
        DashboardReportConfig config = new DashboardReportConfig();
        config.setBaseUrl("https://cortex.inferrix.com");
        config.setDashboardId("22222222-2222-2222-2222-222222222222");
        config.setUseDashboardTimewindow(true);
        config.setTimewindow(JacksonUtil.newObjectNode().put("displayValue", "ignored-because-useDashboardTimewindow"));
        config.setUserId(userId.toString());

        when(systemSecurityService.createUserAccessToken(tenantId, userId)).thenReturn("minted-jwt");
        when(renderer.renderDashboard(anyString(), anyString(), any(RenderOptions.class)))
                .thenReturn(ReportData.builder().data(new byte[]{1}).name("n").contentType("application/pdf").build());

        service.generateReport(tenantId, config);

        ArgumentCaptor<RenderOptions> optionsCaptor = ArgumentCaptor.forClass(RenderOptions.class);
        verify(renderer).renderDashboard(anyString(), anyString(), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getTimewindow()).isNull();
    }

    /**
     * Carries Task 10's finding forward: {@code JwtTokenFactory.createAccessJwtToken} fails an
     * authority-less user with {@link IllegalArgumentException}, not an
     * {@code AuthenticationException}. This must surface out of {@code generateReport} unmodified
     * (not swallowed, not re-typed as a {@link org.thingsboard.server.service.report.render.ReportRenderException})
     * so a later controller layer can still catch it by its real type — and the renderer must never
     * be invoked once the mint has already failed.
     */
    @Test
    void generateReportAuthorityLessUserMintFailureSurfacesCleanly() {
        DashboardReportConfig config = new DashboardReportConfig();
        config.setBaseUrl("https://cortex.inferrix.com");
        config.setDashboardId("33333333-3333-3333-3333-333333333333");
        config.setUserId(userId.toString());

        when(systemSecurityService.createUserAccessToken(tenantId, userId))
                .thenThrow(new IllegalArgumentException("User doesn't have any privileges"));

        assertThatThrownBy(() -> service.generateReport(tenantId, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User doesn't have any privileges");

        verify(renderer, never()).renderDashboard(any(), any(), any());
    }

}
