// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.dashboardreport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Render output DTO for subsystem A (dashboard reports) — mirrors
 * {@link org.thingsboard.server.common.data.report.ReportData} for the on-demand download path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardReportData {

    private byte[] data;
    private String name;
    private String contentType;

}
