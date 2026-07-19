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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
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

    private static final String DASHBOARD_COMPONENT_TYPE = "DASHBOARD";
    private static final String DASHBOARD_REPORT_TYPE = "pdf";

    private final DashboardReportService dashboardReportService;
    private final ReportTemplateService reportTemplateService;

    @Override
    public ReportData generateReport(ReportTask task) {
        ReportTemplate template = reportTemplateService.findReportTemplateById(task.getTenantId(), task.getReportTemplateId());
        JsonNode dashboardComponent = findDashboardComponent(template != null ? template.getConfiguration() : null);
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
     * R1 renders only the {@code DASHBOARD} component; returns the first array element under
     * {@code components} whose {@code type} equals {@code "DASHBOARD"} (spec Appendix A). Defensive
     * against a missing {@code configuration}, a missing/non-array {@code components}, or a
     * component missing its own {@code type} — all treated as "no dashboard component found" rather
     * than a hard parse failure, since {@code configuration} is an unvalidated jsonb passthrough
     * (§2.4).
     */
    private static JsonNode findDashboardComponent(JsonNode configuration) {
        JsonNode components = JacksonUtil.getSafely(configuration, "components");
        if (components == null || !components.isArray()) {
            return null;
        }
        for (JsonNode component : components) {
            JsonNode type = component.get("type");
            if (type != null && DASHBOARD_COMPONENT_TYPE.equals(type.asText())) {
                return component;
            }
        }
        return null;
    }

    /**
     * {@code pageWidth} (Appendix A: {@code DASHBOARD} → {@code dashboardId, state, timewindow,
     * pageWidth}) is deliberately not forwarded here: {@link DashboardReportConfig} (Task 13) has no
     * {@code pageWidth} field to carry it, so {@code RenderOptions.pageWidth} stays null on this call
     * path exactly as Task 13's own report flagged ("dead-in-practice on this one call path" until a
     * future task builds the templated path). Threading it through needs a {@link
     * DashboardReportConfig} field plus a {@code DefaultDashboardReportService.buildRenderOptions}
     * change — both outside this task's file scope; see the Task 14 report's Concerns.
     * <p>
     * {@code namePattern} is also left unset: unlike {@link DashboardReportConfig} (the on-demand
     * path), the {@code DASHBOARD} component schema carries no {@code namePattern} field (Appendix
     * A), so the renderer's existing default ({@code ReportUtils.DEFAULT_REPORT_NAME_PATTERN}) is
     * used, exactly as it already is for any on-demand caller that omits one.
     */
    private static DashboardReportConfig buildDashboardReportConfig(ReportTask task, JsonNode dashboardComponent) {
        DashboardReportConfig config = new DashboardReportConfig();
        config.setDashboardId(textOrNull(dashboardComponent, "dashboardId"));
        config.setState(textOrNull(dashboardComponent, "state"));
        JsonNode timewindow = JacksonUtil.getSafely(dashboardComponent, "timewindow");
        config.setTimewindow(timewindow != null && !timewindow.isNull() ? timewindow : null);
        config.setUserId(task.getUserId() != null ? task.getUserId().toString() : null);
        config.setTimezone(task.getTimezone());
        config.setType(DASHBOARD_REPORT_TYPE);
        return config;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = JacksonUtil.getSafely(node, field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

}
