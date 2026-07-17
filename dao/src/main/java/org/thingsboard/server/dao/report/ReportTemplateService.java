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
