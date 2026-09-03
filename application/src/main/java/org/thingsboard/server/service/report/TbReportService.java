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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.context.TbReportCtxProvider;
import org.thingsboard.server.service.report.render.ReportRenderException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * PE {@code TbReportService} — the {@link ReportTask} → persisted {@link Report} dispatcher (design
 * spec §6.1). Loads the {@link ReportTemplate}, builds the per-render {@link TbReportCtx} (via {@link
 * TbReportCtxProvider}), picks the {@link TbReportRenderService} registered for the template's {@link
 * TbReportFormat}, renders, then persists the bytes via the dao {@code ReportService}
 * ({@code createReport}). Both engines auto-register by their {@link TbReportRenderService#getFormat()}:
 * {@link PdfReportService} ({@link TbReportFormat#PDF}, R2a) and {@link CsvReportService}
 * ({@link TbReportFormat#CSV}, R2b Task I).
 * <p>
 * Guarded by the same {@code reports.renderer.enabled} property as the render service and ctx provider
 * it injects (Task 13 / R2a): all three appear/absent together, so the platform boots renderer-off.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class TbReportService {

    private final org.thingsboard.server.dao.report.ReportService reportDao;
    private final ReportTemplateService reportTemplateService;
    private final TbReportCtxProvider ctxProvider;
    private final Map<TbReportFormat, TbReportRenderService> renderServices = new EnumMap<>(TbReportFormat.class);

    public TbReportService(org.thingsboard.server.dao.report.ReportService reportDao,
                           ReportTemplateService reportTemplateService,
                           TbReportCtxProvider ctxProvider,
                           List<TbReportRenderService> renderers) {
        this.reportDao = reportDao;
        this.reportTemplateService = reportTemplateService;
        this.ctxProvider = ctxProvider;
        // Both PdfReportService (PDF) and CsvReportService (CSV) auto-register here by getFormat() when the
        // renderer is enabled — nothing format-specific to add.
        renderers.forEach(renderService -> renderServices.put(renderService.getFormat(), renderService));
    }

    /**
     * Renders {@code task}'s report template and persists the result via the dao {@code
     * ReportService}. Reports are immutable, generated artifacts (dao {@code ReportService}
     * javadoc) — this always creates a new row, never updates one.
     */
    public Report generateReport(ReportTask task) {
        log.info("Generating report for tenant [{}], template [{}]", task.getTenantId(), task.getReportTemplateId());
        try {
            ReportTemplate template = loadTemplate(task);
            ReportData reportData = render(task, template.getFormat());
            Report report = buildReport(task, template, reportData);
            Report saved = reportDao.createReport(report, reportData.getData());
            log.info("Persisted report [{}] for tenant [{}], template [{}], {} bytes",
                    saved.getId(), task.getTenantId(), task.getReportTemplateId(),
                    reportData.getData() != null ? reportData.getData().length : 0);
            return saved;
        } catch (RuntimeException e) {
            log.error("Failed to generate report for tenant [{}], template [{}]: {}",
                    task.getTenantId(), task.getReportTemplateId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Synchronous render for the {@code /api/v2/report/test} path (Task 20) — same render path as
     * {@link #generateReport}, but the result is returned directly and never persisted.
     */
    public ReportData generateTestReport(ReportTask task) {
        log.info("Generating test report (no persist) for tenant [{}], template [{}]", task.getTenantId(), task.getReportTemplateId());
        try {
            // The designer's preview posts the in-memory config of a template that may never have been
            // saved, so there is no id to load and loadTemplate would fail validation ("Incorrect
            // reportTemplateId null"). Nothing downstream needs the persisted row: the render pipeline
            // reads the configuration off the task via TbReportCtx (LocalTbReportCtxProvider), and the
            // template is consulted only to pick the engine. So take the format from the inline config
            // when there is one, and fall back to the stored template otherwise.
            ReportTemplateConfig config = task.getReportTemplateConfig();
            TbReportFormat format = config != null ? config.getFormat() : loadTemplate(task).getFormat();
            return render(task, format);
        } catch (RuntimeException e) {
            log.error("Failed to generate test report for tenant [{}], template [{}]: {}",
                    task.getTenantId(), task.getReportTemplateId(), e.getMessage(), e);
            throw e;
        }
    }

    private ReportTemplate loadTemplate(ReportTask task) {
        ReportTemplate template = reportTemplateService.findReportTemplateById(task.getTenantId(), task.getReportTemplateId());
        if (template == null) {
            throw new ReportRenderException("Report template not found: " + task.getReportTemplateId());
        }
        return template;
    }

    private ReportData render(ReportTask task, TbReportFormat format) {
        TbReportRenderService renderService = renderServices.get(format);
        if (renderService == null) {
            // Defensive: with the renderer enabled, PDF and CSV both register — this guards a format that has
            // no engine (e.g. a future TbReportFormat added without one), and is exercised by TbReportServiceTest.
            throw new ReportRenderException("No render service registered for report format: " + format);
        }
        // The ctx is Closeable (R2b): close() releases the per-render TBEL post-processing scripts the data
        // layer compiles. try-with-resources guarantees that even if the render throws.
        try (TbReportCtx ctx = ctxProvider.newContext(task)) {
            return renderService.generateReport(task, ctx);
        }
    }

    private static Report buildReport(ReportTask task, ReportTemplate template, ReportData reportData) {
        Report report = new Report();
        report.setTenantId(task.getTenantId());
        report.setCustomerId(task.getCustomerId());
        report.setTemplateId(template.getId().getId());
        report.setFormat(template.getFormat());
        report.setName(reportData.getName());
        report.setUserId(task.getUserId());
        return report;
    }

}
