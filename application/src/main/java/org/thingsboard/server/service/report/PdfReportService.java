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
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.service.report.render.ReportRenderException;

/**
 * R1's only {@link TbReportRenderService}: renders a {@link ReportTask} whose report template
 * configuration contains a {@code DASHBOARD} component (design spec §6.1, Appendix A) by delegating
 * to {@link DashboardReportService} — the same renderer Task 13 wired for the on-demand/test
 * download path. Non-dashboard components (entity/alarm/timeseries tables, rich text, headings, ...)
 * and the CSV format are R2 (spec §18); a template with no dashboard component is rejected outright.
 */
@Slf4j
@RequiredArgsConstructor
public class PdfReportService implements TbReportRenderService {

    private static final String DASHBOARD_REPORT_TYPE = "pdf";

    private final DashboardReportService dashboardReportService;
    private final ReportTemplateService reportTemplateService;

    /**
     * Base URL the headless renderer navigates to for the scheduled/template render path (design
     * spec §13 {@code reports.renderer.base_url}). Unlike the on-demand path ({@code
     * DashboardReportController}, Task 20), a scheduled render has no inbound {@code HttpServletRequest}
     * to derive a base URL from ({@code MiscUtils.constructBaseUrl}) — it's driven off a job, not a
     * controller call — so it's sourced from config instead (Task 26).
     */
    private final String baseUrl;

    @Override
    public ReportData generateReport(ReportTask task) {
        ReportTemplate template = reportTemplateService.findReportTemplateById(task.getTenantId(), task.getReportTemplateId());
        DashboardComponent dashboardComponent = findDashboardComponent(template != null ? template.getConfiguration() : null);
        if (dashboardComponent == null) {
            log.warn("No DASHBOARD component in template [{}] for tenant [{}]; R1 renders dashboard-component templates only",
                    task.getReportTemplateId(), task.getTenantId());
            // R2: non-dashboard components (ENTITY_TABLE, ALARM_TABLE, RICH_TEXT, ...) render here.
            throw new ReportRenderException("R1 supports dashboard-component templates only");
        }
        DashboardReportConfig config = buildDashboardReportConfig(task, dashboardComponent);
        return dashboardReportService.generateReport(task.getTenantId(), config);
    }

    @Override
    public TbReportFormat getFormat() {
        return TbReportFormat.PDF;
    }

    /**
     * R1 renders only the {@code DASHBOARD} component; returns the first {@link DashboardComponent}
     * in the typed {@code components} list (spec Appendix A). Defensive against a missing
     * {@code configuration} or a missing/empty {@code components} list — treated as "no dashboard
     * component found" rather than a hard failure.
     */
    private static DashboardComponent findDashboardComponent(ReportTemplateConfig configuration) {
        if (configuration == null || configuration.getComponents() == null) {
            return null;
        }
        for (ReportComponent component : configuration.getComponents()) {
            if (component != null && component.getType() == ReportComponentType.DASHBOARD) {
                return (DashboardComponent) component;
            }
        }
        return null;
    }

    /**
     * Reads {@code dashboardId}/{@code state}/{@code timewindow} off the DASHBOARD component's nested
     * {@link DashboardReportConfig} ({@link DashboardComponent#getConfig()}, PE shape) and overlays
     * the task-derived render fields (user/timezone/base URL).
     * <p>
     * Dashboard sizing ({@code widthType}/{@code customWidth} on the typed component; R1's flat
     * {@code pageWidth} was dropped in R2a) is not forwarded — {@link DashboardReportConfig} carries
     * no sizing field, so the on-demand renderer default applies. {@code namePattern} is likewise
     * left unset so the renderer default ({@code ReportUtils.DEFAULT_REPORT_NAME_PATTERN}) is used.
     */
    private DashboardReportConfig buildDashboardReportConfig(ReportTask task, DashboardComponent dashboardComponent) {
        DashboardReportConfig componentConfig = dashboardComponent.getConfig();
        DashboardReportConfig config = new DashboardReportConfig();
        if (componentConfig != null) {
            config.setDashboardId(componentConfig.getDashboardId());
            config.setState(componentConfig.getState());
            config.setTimewindow(componentConfig.getTimewindow());
        }
        config.setUserId(task.getUserId() != null ? task.getUserId().toString() : null);
        config.setTimezone(task.getTimezone());
        config.setType(DASHBOARD_REPORT_TYPE);
        config.setBaseUrl(baseUrl);
        return config;
    }

}
