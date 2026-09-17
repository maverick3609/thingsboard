// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.report;

import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.ReportInfoQuery;
import org.thingsboard.server.dao.entity.EntityDaoService;

/**
 * Reports are immutable, generated artifacts: there is intentionally no update method. Attempting
 * to route an already-persisted {@link Report} back through {@link #createReport} is rejected by
 * {@link org.thingsboard.server.dao.service.validator.ReportDataValidator}.
 */
public interface ReportService extends EntityDaoService {

    Report createReport(Report report, byte[] data);

    Report findReportById(TenantId tenantId, ReportId reportId);

    byte[] getReportData(TenantId tenantId, ReportId reportId);

    ReportInfo findReportInfoById(TenantId tenantId, ReportId reportId);

    PageData<ReportInfo> findReports(TenantId tenantId, ReportInfoQuery query);

    void deleteReport(TenantId tenantId, ReportId reportId);

    void deleteReportsByTenantId(TenantId tenantId);

    void deleteReportsByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId);

}
