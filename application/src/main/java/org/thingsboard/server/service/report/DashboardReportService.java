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

import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.ReportData;

/**
 * On-demand / test dashboard-report render path (design spec §6.1, §6.3). PE lifts this interface
 * into {@code rule-engine-api} ({@code org.thingsboard.rule.engine.api.DashboardReportService}) so
 * a rule node can call it; that move is R2 work alongside the rule node. R1 keeps it local to
 * {@code application} — no upstream-module touch.
 */
public interface DashboardReportService {

    ReportData generateReport(TenantId tenantId, DashboardReportConfig config);

}
