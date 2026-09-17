// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.report;

import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.dao.entity.EntityDaoService;

import java.util.List;

public interface ReportTemplateService extends EntityDaoService {

    ReportTemplate saveReportTemplate(ReportTemplate reportTemplate);

    ReportTemplate findReportTemplateById(TenantId tenantId, ReportTemplateId reportTemplateId);

    ReportTemplateInfo findReportTemplateInfoById(TenantId tenantId, ReportTemplateId reportTemplateId);

    PageData<ReportTemplateInfo> findReportTemplateInfos(TenantId tenantId, ReportTemplateQuery query);

    List<ReportTemplate> findReportTemplatesByIds(TenantId tenantId, List<ReportTemplateId> reportTemplateIds);

    void deleteReportTemplate(TenantId tenantId, ReportTemplateId reportTemplateId);

    void deleteByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId);

}
