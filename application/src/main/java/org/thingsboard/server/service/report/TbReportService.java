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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.service.report.render.ReportRenderException;

import java.util.EnumMap;
import java.util.Map;

/**
 * PE {@code TbReportService} — the {@link ReportTask} → persisted {@link Report} dispatcher (design
 * spec §6.1). Loads the {@link ReportTemplate}, picks the {@link TbReportRenderService} registered
 * for its {@link TbReportFormat}, renders, then persists the bytes via the dao {@code ReportService}
 * ({@code createReport}). R1 registers only {@link PdfReportService} ({@link TbReportFormat#PDF});
 * {@link TbReportFormat#CSV} has no renderer yet (R2).
 * <p>
 * Guarded by the same {@code reports.renderer.enabled} property as {@code DefaultDashboardReportService}
 * / {@code PlaywrightWebReportRenderer} (Task 13): this service's constructor builds a {@link
 * PdfReportService} bound to the (conditional) {@link DashboardReportService} bean, so without the
 * guard Spring would fail to start any environment that hasn't opted into the
 * still-disabled-by-default renderer.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class TbReportService {

    private final org.thingsboard.server.dao.report.ReportService reportDao;
    private final ReportTemplateService reportTemplateService;
    private final Map<TbReportFormat, TbReportRenderService> renderServices = new EnumMap<>(TbReportFormat.class);

    public TbReportService(DashboardReportService dashboardReportService,
                            org.thingsboard.server.dao.report.ReportService reportDao,
                            ReportTemplateService reportTemplateService,
                            @Value("${reports.renderer.base_url:http://localhost:8080}") String rendererBaseUrl) {
        this.reportDao = reportDao;
        this.reportTemplateService = reportTemplateService;
        register(new PdfReportService(dashboardReportService, reportTemplateService, rendererBaseUrl));
        // R2: register a CsvReportService (TbReportFormat.CSV) here once it exists.
    }

    private void register(TbReportRenderService renderService) {
        renderServices.put(renderService.getFormat(), renderService);
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
            ReportData reportData = render(task, template);
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
            ReportTemplate template = loadTemplate(task);
            return render(task, template);
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

    private ReportData render(ReportTask task, ReportTemplate template) {
        TbReportFormat format = template.getFormat();
        TbReportRenderService renderService = renderServices.get(format);
        if (renderService == null) {
            // R2: TbReportFormat.CSV has no renderer registered yet.
            throw new ReportRenderException("No render service registered for report format: " + format);
        }
        return renderService.generateReport(task);
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
