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
