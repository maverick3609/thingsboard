// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.report;

import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.dao.Dao;
import org.thingsboard.server.dao.ExportableEntityDao;
import org.thingsboard.server.dao.TenantEntityDao;

import java.util.List;
import java.util.UUID;

public interface ReportTemplateDao extends Dao<ReportTemplate>, TenantEntityDao<ReportTemplate>, ExportableEntityDao<ReportTemplateId, ReportTemplate> {

    List<ReportTemplate> findReportTemplatesByIds(UUID tenantId, List<UUID> reportTemplateIds);

}
