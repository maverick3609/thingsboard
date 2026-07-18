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
