// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.rule.engine.api;

import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportData;

/**
 * Renders a dashboard to a report artifact. Lives in {@code rule-engine-api} — same module PE puts
 * it in — because {@link org.thingsboard.rule.engine.report.TbGenerateReportNode} reaches it through
 * {@link TbContext#getDashboardReportService()}, and {@code rule-engine-components} cannot see
 * {@code application}. The R1 port kept it under {@code application} with a note that this move was
 * "R2 work alongside the rule node"; this is that move.
 * <p>
 * The only implementation ({@code DefaultDashboardReportService}) is
 * {@code @ConditionalOnProperty(reports.renderer.enabled=true)}, so callers must treat it as
 * possibly absent — {@link TbContext#getDashboardReportService()} returns {@code null} when the
 * renderer is off.
 */
public interface DashboardReportService {

    /**
     * Renders and returns the bytes without persisting anything — the on-demand / test-download path.
     */
    ReportData generateReport(TenantId tenantId, DashboardReportConfig config);

    /**
     * Renders and stores the result as a {@link Report}, returning the persisted entity.
     * <p>
     * Persistence lives behind this seam rather than in the calling rule node so the node needs only
     * one {@link TbContext} getter: resolving the report's owner and format needs the user and report
     * DAOs, which {@code rule-engine-components} cannot reach. This is where PE's node would instead
     * have saved a {@code BlobEntity} — a generic blob store this fork does not have; {@link Report}
     * is the equivalent artifact, and it is what {@code DefaultMailService} already knows how to
     * attach to an outgoing email.
     */
    Report generateAndSaveReport(TenantId tenantId, DashboardReportConfig config);

}
