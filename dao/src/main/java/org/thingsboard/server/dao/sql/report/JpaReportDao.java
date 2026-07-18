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
package org.thingsboard.server.dao.sql.report;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.ReportInfoQuery;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.model.sql.ReportEntity;
import org.thingsboard.server.dao.model.sql.ReportInfoEntity;
import org.thingsboard.server.dao.report.ReportDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.UUID;

@Component
@SqlDao
@Slf4j
public class JpaReportDao extends JpaAbstractDao<ReportEntity, Report> implements ReportDao {

    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ReportInfoRepository reportInfoRepository;

    @Override
    protected Class<ReportEntity> getEntityClass() {
        return ReportEntity.class;
    }

    @Override
    protected JpaRepository<ReportEntity, UUID> getRepository() {
        return reportRepository;
    }

    @Override
    public void saveData(TenantId tenantId, ReportId id, byte[] data) {
        log.debug("Try to save report data, tenantId [{}], id [{}]", tenantId, id);
        reportRepository.saveData(id.getId(), data);
    }

    @Override
    public byte[] getData(TenantId tenantId, ReportId id) {
        log.debug("Try to get report data, tenantId [{}], id [{}]", tenantId, id);
        return reportRepository.getDataById(id.getId());
    }

    @Override
    public ReportInfo findInfoById(TenantId tenantId, UUID id) {
        log.debug("Try to find report info by id [{}]", id);
        return DaoUtil.getData(reportInfoRepository.findReportInfoById(id));
    }

    @Override
    public PageData<ReportInfo> findReports(TenantId tenantId, ReportInfoQuery query) {
        log.debug("Try to find reports by tenantId [{}] and query [{}]", tenantId, query);
        UUID reportTemplateId = query.getReportTemplateId() != null ? query.getReportTemplateId().getId() : null;
        UUID userId = query.getUserId() != null ? query.getUserId().getId() : null;
        String searchText = Strings.emptyToNull(query.getPageLink().getTextSearch());
        Pageable pageable = DaoUtil.toPageable(query.getPageLink(), ReportInfoEntity.reportInfoColumnMap);
        PageData<ReportInfo> result;
        if (query.isIncludeCustomers()) {
            result = DaoUtil.toPageData(reportInfoRepository.findTenantReportInfosIncludingCustomers(
                    tenantId.getId(), reportTemplateId, userId, searchText, pageable));
        } else {
            result = DaoUtil.toPageData(reportInfoRepository.findTenantReportInfos(
                    tenantId.getId(), ModelConstants.NULL_UUID, reportTemplateId, userId, searchText, pageable));
        }
        log.debug("Found [{}] reports by tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        log.debug("Try to delete reports by tenantId [{}]", tenantId);
        reportRepository.deleteByTenantId(tenantId.getId());
    }

    @Override
    public void deleteByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId) {
        log.debug("Try to delete reports by tenantId [{}] and customerId [{}]", tenantId, customerId);
        reportRepository.deleteByTenantIdAndCustomerId(tenantId.getId(), customerId.getId());
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.REPORT;
    }

}
