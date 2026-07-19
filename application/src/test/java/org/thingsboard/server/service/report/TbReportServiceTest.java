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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.service.report.render.ReportRenderException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers Task 14: {@link TbReportService} dispatches a {@link ReportTask} to the {@link
 * TbReportRenderService} registered for its template's format ({@link PdfReportService} for {@link
 * TbReportFormat#PDF}), then persists the rendered bytes via the dao {@code ReportService}. {@link
 * DashboardReportService} and both dao collaborators are mocked — no real render or persist.
 */
@ExtendWith(MockitoExtension.class)
class TbReportServiceTest {

    @Mock
    private DashboardReportService dashboardReportService;
    @Mock
    private org.thingsboard.server.dao.report.ReportService reportDao;
    @Mock
    private ReportTemplateService templateDao;

    @InjectMocks
    private TbReportService svc; // wires PdfReportService(dashboardReportService, templateDao)

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());

    @Test
    void generatesAndPersistsDashboardTemplate() {
        ReportTemplate tmpl = dashboardTemplate();
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);
        ReportData rendered = ReportData.builder()
                .data("%PDF-x".getBytes()).name("r.pdf").contentType("application/pdf").build();
        when(dashboardReportService.generateReport(eq(tenantId), any(DashboardReportConfig.class))).thenReturn(rendered);
        when(reportDao.createReport(any(Report.class), any(byte[].class))).thenAnswer(invocation -> {
            Report saved = invocation.getArgument(0);
            saved.setId(new ReportId(UUID.randomUUID()));
            return saved;
        });

        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).customerId(customerId).reportTemplateId(tmpl.getId())
                .timezone("Europe/Kiev").userId(userId).accessToken("jwt").build();

        Report r = svc.generateReport(task);

        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(eq(tenantId), configCaptor.capture());
        DashboardReportConfig config = configCaptor.getValue();
        assertThat(config.getDashboardId()).isEqualTo("d1");
        assertThat(config.getState()).isEqualTo("state-blob");
        assertThat(config.getTimewindow().get("displayValue").asText()).isEqualTo("");
        assertThat(config.getType()).isEqualTo("pdf");
        assertThat(config.getTimezone()).isEqualTo("Europe/Kiev");
        assertThat(config.getUserId()).isEqualTo(userId.toString());

        verify(reportDao).createReport(any(Report.class), eq("%PDF-x".getBytes()));
        assertThat(r).isNotNull();
        assertThat(r.getId()).isNotNull();
        assertThat(r.getName()).isEqualTo("r.pdf");
        assertThat(r.getFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(r.getTenantId()).isEqualTo(tenantId);
        assertThat(r.getCustomerId()).isEqualTo(customerId);
        assertThat(r.getUserId()).isEqualTo(userId);
        assertThat(r.getTemplateId()).isEqualTo(tmpl.getId().getId());
    }

    @Test
    void templateWithoutDashboardComponentThrowsReportRenderException() {
        ReportTemplate tmpl = templateWithoutDashboardComponent();
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);
        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(tmpl.getId()).timezone("UTC").userId(userId).build();

        assertThatThrownBy(() -> svc.generateReport(task))
                .isInstanceOf(ReportRenderException.class)
                .hasMessage("R1 supports dashboard-component templates only");

        verify(dashboardReportService, never()).generateReport(any(), any());
        verify(reportDao, never()).createReport(any(), any());
    }

    @Test
    void generateTestReportRendersWithoutPersisting() {
        ReportTemplate tmpl = dashboardTemplate();
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);
        ReportData rendered = ReportData.builder()
                .data(new byte[]{1, 2, 3}).name("test.pdf").contentType("application/pdf").build();
        when(dashboardReportService.generateReport(eq(tenantId), any(DashboardReportConfig.class))).thenReturn(rendered);

        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(tmpl.getId()).timezone("UTC").userId(userId).build();

        ReportData result = svc.generateTestReport(task);

        assertThat(result).isSameAs(rendered);
        verify(reportDao, never()).createReport(any(), any());
    }

    private ReportTemplate dashboardTemplate() {
        ReportTemplate template = new ReportTemplate(new ReportTemplateId(UUID.randomUUID()));
        template.setTenantId(tenantId);
        template.setName("Weekly");
        template.setFormat(TbReportFormat.PDF);
        template.setType(ReportTemplateType.REPORT);
        template.setConfiguration(JacksonUtil.toJsonNode(
                "{\"type\":\"PDF\",\"components\":[{\"type\":\"DASHBOARD\",\"dashboardId\":\"d1\",\"state\":\"state-blob\",\"timewindow\":{\"displayValue\":\"\"},\"pageWidth\":800}]}"));
        return template;
    }

    private ReportTemplate templateWithoutDashboardComponent() {
        ReportTemplate template = new ReportTemplate(new ReportTemplateId(UUID.randomUUID()));
        template.setTenantId(tenantId);
        template.setName("No dashboard");
        template.setFormat(TbReportFormat.PDF);
        template.setType(ReportTemplateType.REPORT);
        template.setConfiguration(JacksonUtil.toJsonNode(
                "{\"type\":\"PDF\",\"components\":[{\"type\":\"RICH_TEXT\",\"content\":\"<p>hi</p>\"}]}"));
        return template;
    }

}
