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
package org.thingsboard.server.common.data.dashboardreport;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * On-demand dashboard-report download body, and the legacy {@code generateDashboardReport}
 * scheduler event's {@code msgBody.reportConfig} (design spec §2.3). Field names are
 * PE-verbatim.
 */
@Data
public class DashboardReportConfig {

    private String baseUrl;
    private String dashboardId;
    private String state;
    private String timezone;
    private boolean useDashboardTimewindow;
    private JsonNode timewindow;
    private String namePattern;
    private String type;
    private boolean useCurrentUserCredentials;
    private String userId;

}
