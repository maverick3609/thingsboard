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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.context.TbReportCtxProvider;
import org.thingsboard.server.service.report.render.ReportRenderException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers Task 14 / R2a: {@link TbReportService} dispatches a {@link ReportTask} to the {@link
 * TbReportRenderService} registered for its template's format ({@link PdfReportService} for {@link
 * TbReportFormat#PDF}), building the per-render {@link TbReportCtx} via the {@link TbReportCtxProvider}
 * first, then persisting the rendered bytes via the dao {@code ReportService}. The render service, ctx
 * provider and both dao collaborators are mocked — the engine itself is exercised by {@link
 * PdfReportServiceTest}; this is the dispatch+persist contract only.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TbReportServiceTest {

    @Mock
    private org.thingsboard.server.dao.report.ReportService reportDao;
    @Mock
    private ReportTemplateService templateDao;
    @Mock
    private TbReportCtxProvider ctxProvider;
    @Mock
    private TbReportRenderService pdfRenderService;

    private TbReportService svc;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        when(pdfRenderService.getFormat()).thenReturn(TbReportFormat.PDF);
        svc = new TbReportService(reportDao, templateDao, ctxProvider, List.of(pdfRenderService));
    }

    @Test
    void generatesAndPersistsViaFormatRenderService() {
        ReportTemplate tmpl = pdfTemplate();
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);

        TbReportCtx ctx = TbReportCtx.builder().tenantId(tenantId).build();
        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).customerId(customerId).reportTemplateId(tmpl.getId())
                .timezone("Europe/Kiev").userId(userId).accessToken("jwt").build();
        when(ctxProvider.newContext(task)).thenReturn(ctx);

        ReportData rendered = ReportData.builder()
                .data("%PDF-x".getBytes()).name("r.pdf").contentType("application/pdf").build();
        when(pdfRenderService.generateReport(eq(task), eq(ctx))).thenReturn(rendered);

        ReportId savedId = new ReportId(UUID.randomUUID());
        when(reportDao.createReport(any(Report.class), any(byte[].class))).thenAnswer(invocation -> {
            // Return a DISTINCT Report (copy) with the dao-assigned id so the identity assertion
            // below pins TbReportService's `return saved;` (dao response), not its pre-save local.
            Report preSave = invocation.getArgument(0);
            Report saved = new Report(preSave);
            saved.setId(savedId);
            return saved;
        });

        Report r = svc.generateReport(task);

        verify(ctxProvider).newContext(task);
        verify(pdfRenderService).generateReport(task, ctx);
        verify(reportDao).createReport(any(Report.class), eq("%PDF-x".getBytes()));
        assertThat(r).isNotNull();
        assertThat(r.getId()).isEqualTo(savedId);
        assertThat(r.getName()).isEqualTo("r.pdf");
        assertThat(r.getFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(r.getTenantId()).isEqualTo(tenantId);
        assertThat(r.getCustomerId()).isEqualTo(customerId);
        assertThat(r.getUserId()).isEqualTo(userId);
        assertThat(r.getTemplateId()).isEqualTo(tmpl.getId().getId());
    }

    @Test
    void generateTestReportRendersWithoutPersisting() {
        ReportTemplate tmpl = pdfTemplate();
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);
        TbReportCtx ctx = TbReportCtx.builder().tenantId(tenantId).build();
        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(tmpl.getId()).timezone("UTC").userId(userId).build();
        when(ctxProvider.newContext(task)).thenReturn(ctx);
        ReportData rendered = ReportData.builder()
                .data(new byte[]{1, 2, 3}).name("test.pdf").contentType("application/pdf").build();
        when(pdfRenderService.generateReport(eq(task), eq(ctx))).thenReturn(rendered);

        ReportData result = svc.generateTestReport(task);

        assertThat(result).isSameAs(rendered);
        verify(reportDao, never()).createReport(any(), any());
    }

    @Test
    void unknownFormatWithNoRenderServiceThrows() {
        // The generic guard: a format with no registered engine must reject cleanly. This harness registers only
        // the PDF engine (setUp) — so a CSV template exercises the guard here, even though CsvReportService
        // registers CSV in production (R2b). See CsvReportServiceTest for the CSV-routed dispatch.
        ReportTemplate tmpl = pdfTemplate();
        tmpl.setFormat(TbReportFormat.CSV);
        when(templateDao.findReportTemplateById(tenantId, tmpl.getId())).thenReturn(tmpl);
        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(tmpl.getId()).timezone("UTC").userId(userId).build();

        assertThatThrownBy(() -> svc.generateReport(task))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("No render service registered for report format: CSV");

        verify(ctxProvider, never()).newContext(any());
        verify(reportDao, never()).createReport(any(), any());
    }

    @Test
    void missingTemplateThrows() {
        ReportTemplateId templateId = new ReportTemplateId(UUID.randomUUID());
        when(templateDao.findReportTemplateById(tenantId, templateId)).thenReturn(null);
        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(templateId).userId(userId).build();

        assertThatThrownBy(() -> svc.generateReport(task))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("Report template not found");

        verify(reportDao, never()).createReport(any(), any());
    }

    private ReportTemplate pdfTemplate() {
        ReportTemplate template = new ReportTemplate(new ReportTemplateId(UUID.randomUUID()));
        template.setTenantId(tenantId);
        template.setName("Weekly");
        template.setFormat(TbReportFormat.PDF);
        template.setType(ReportTemplateType.REPORT);
        return template;
    }

}
