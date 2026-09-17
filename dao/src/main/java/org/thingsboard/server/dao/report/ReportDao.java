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
import org.thingsboard.server.dao.Dao;

import java.util.UUID;

public interface ReportDao extends Dao<Report> {

    void saveData(TenantId tenantId, ReportId id, byte[] data);

    byte[] getData(TenantId tenantId, ReportId id);

    ReportInfo findInfoById(TenantId tenantId, UUID id);

    PageData<ReportInfo> findReports(TenantId tenantId, ReportInfoQuery query);

    void deleteByTenantId(TenantId tenantId);

    void deleteByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId);

}
