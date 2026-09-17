// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DashboardComponent extends AbstractImageComponent {

    @NotNull
    @Schema(description = "Dashboard report configuration.")
    private DashboardReportConfig config;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.DASHBOARD;
    }
}
