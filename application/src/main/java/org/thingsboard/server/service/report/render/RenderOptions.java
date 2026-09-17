// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.render;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What {@link PlaywrightWebReportRenderer#renderDashboard} needs to drive one {@code openReport}
 * handshake (design spec §6.3) and capture the result — renderer-facing, not wire-facing, so it's
 * populated differently by different callers: {@code DefaultDashboardReportService} builds it from
 * a {@link org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig} (on-demand /
 * test-report path, no {@code pageWidth} — that DTO doesn't carry one), the R2 templated-report
 * path will build it from a {@code ReportTemplateConfig}'s {@code DashboardComponent} (which does
 * carry {@code pageWidth}, spec §2.4/Appendix A).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RenderOptions {

    private String dashboardId;
    private String state;
    private JsonNode timewindow;
    private String type;
    private Integer pageWidth;
    private String namePattern;
    private String timezone;

}
