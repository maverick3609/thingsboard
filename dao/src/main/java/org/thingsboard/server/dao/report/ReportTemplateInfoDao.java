// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.report;

import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.dao.Dao;

import java.util.List;
import java.util.UUID;

public interface ReportTemplateInfoDao extends Dao<ReportTemplateInfo> {

    PageData<ReportTemplateInfo> findReportTemplateInfos(UUID tenantId, ReportTemplateQuery query);

    List<UUID> findReportTemplateIdsByTenantId(UUID tenantId);

    List<UUID> findReportTemplateIdsByTenantIdAndCustomerId(UUID tenantId, UUID customerId);

}
