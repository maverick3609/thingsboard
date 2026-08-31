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
package org.thingsboard.server.controller;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.rule.engine.api.DashboardReportService;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Task 21: {@code /api/report/{dashboardId}/download} and {@code /api/report/test}. The
 * renderer itself ({@code reports.renderer.enabled}) stays OFF; {@link DashboardReportService} is
 * {@code @MockitoBean}-replaced, which both supplies the mock behavior AND makes the controller's
 * {@code Optional<DashboardReportService>} non-empty despite the real {@code
 * @ConditionalOnProperty} bean being absent.
 */
@DaoSqlTest
public class DashboardReportControllerTest extends AbstractControllerTest {

    @MockitoBean
    private DashboardReportService dashboardReportService;

    @Test
    public void testDownloadDashboardReport() throws Exception {
        loginTenantAdmin();
        Dashboard dashboard = new Dashboard();
        dashboard.setTitle("My Dashboard");
        Dashboard savedDashboard = doPost("/api/dashboard", dashboard, Dashboard.class);

        when(dashboardReportService.generateReport(any(), any())).thenReturn(
                ReportData.builder().data("%PDF-x".getBytes()).name("dash.pdf").contentType("application/pdf").build());

        MvcResult mvcResult = doPostAsync("/api/report/" + savedDashboard.getId() + "/download",
                Map.of("type", "pdf", "timezone", "UTC"), 30_000L)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("x-filename")).contains("pdf");
        assertThat(mvcResult.getResponse().getHeader("Content-Disposition")).contains("dash.pdf");
        assertThat(mvcResult.getResponse().getContentAsByteArray()).startsWith("%PDF-".getBytes());

        ArgumentCaptor<TenantId> tenantIdCaptor = ArgumentCaptor.forClass(TenantId.class);
        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(tenantIdCaptor.capture(), configCaptor.capture());
        assertThat(tenantIdCaptor.getValue()).isEqualTo(tenantId);
        DashboardReportConfig config = configCaptor.getValue();
        assertThat(config.getDashboardId()).isEqualTo(savedDashboard.getId().toString());
        assertThat(config.getNamePattern()).startsWith("My Dashboard-");
        assertThat(config.getType()).isEqualTo("pdf");
        assertThat(config.getTimezone()).isEqualTo("UTC");
        assertThat(config.isUseDashboardTimewindow()).isTrue();
        assertThat(config.isUseCurrentUserCredentials()).isTrue();
        assertThat(config.getUserId()).isEqualTo(tenantAdminUserId.getId().toString());
    }

    @Test
    public void testDownloadTestReport() throws Exception {
        loginTenantAdmin();
        Dashboard dashboard = new Dashboard();
        dashboard.setTitle("Test Report Dashboard");
        Dashboard savedDashboard = doPost("/api/dashboard", dashboard, Dashboard.class);

        when(dashboardReportService.generateReport(any(), any())).thenReturn(
                ReportData.builder().data("%PDF-y".getBytes()).name("test.pdf").contentType("application/pdf").build());

        DashboardReportConfig config = new DashboardReportConfig();
        config.setDashboardId(savedDashboard.getId().toString());
        config.setType("pdf");
        config.setTimezone("UTC");
        config.setUseDashboardTimewindow(true);
        config.setUseCurrentUserCredentials(true);
        config.setUserId(tenantAdminUserId.getId().toString());

        MvcResult mvcResult = doPostAsync("/api/report/test?reportsServerEndpointUrl=http://localhost:8383", config, 30_000L)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("x-filename")).contains("pdf");
        assertThat(mvcResult.getResponse().getContentAsByteArray()).startsWith("%PDF-".getBytes());

        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(any(), configCaptor.capture());
        assertThat(configCaptor.getValue().getBaseUrl()).isNotBlank();
    }

    @Test
    public void testDownloadTestReportIgnoresClientSuppliedUserIdAndBaseUrl() throws Exception {
        loginTenantAdmin();
        Dashboard dashboard = new Dashboard();
        dashboard.setTitle("Test Report Dashboard - Untrusted Fields");
        Dashboard savedDashboard = doPost("/api/dashboard", dashboard, Dashboard.class);

        when(dashboardReportService.generateReport(any(), any())).thenReturn(
                ReportData.builder().data("%PDF-z".getBytes()).name("test.pdf").contentType("application/pdf").build());

        String bogusUserId = UUID.randomUUID().toString();
        DashboardReportConfig config = new DashboardReportConfig();
        config.setDashboardId(savedDashboard.getId().toString());
        config.setType("pdf");
        config.setTimezone("UTC");
        config.setUseDashboardTimewindow(true);
        config.setUseCurrentUserCredentials(false);
        config.setUserId(bogusUserId);
        config.setBaseUrl("http://evil.example.com");

        doPostAsync("/api/report/test", config, 30_000L)
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(any(), configCaptor.capture());
        DashboardReportConfig captured = configCaptor.getValue();
        assertThat(captured.getUserId()).isEqualTo(tenantAdminUserId.getId().toString());
        assertThat(captured.getUserId()).isNotEqualTo(bogusUserId);
        assertThat(captured.getBaseUrl()).isNotEqualTo("http://evil.example.com");
        assertThat(captured.isUseCurrentUserCredentials()).isTrue();
    }

}
