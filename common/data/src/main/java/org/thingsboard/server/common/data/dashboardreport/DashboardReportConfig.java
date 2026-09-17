// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
