// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.report;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity;
import org.thingsboard.server.dao.report.ReportTemplateInfoDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.List;
import java.util.UUID;

import static org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity.reportTemplateInfoColumnMap;

@Component
@SqlDao
@Slf4j
public class JpaReportTemplateInfoDao extends JpaAbstractDao<ReportTemplateInfoEntity, ReportTemplateInfo> implements ReportTemplateInfoDao {

    @Autowired
    private ReportTemplateInfoRepository reportTemplateInfoRepository;

    @Override
    protected Class<ReportTemplateInfoEntity> getEntityClass() {
        return ReportTemplateInfoEntity.class;
    }

    @Override
    protected JpaRepository<ReportTemplateInfoEntity, UUID> getRepository() {
        return reportTemplateInfoRepository;
    }

    @Override
    public ReportTemplateInfo findById(TenantId tenantId, UUID id) {
        log.debug("Try to find report template info by id [{}]", id);
        return DaoUtil.getData(reportTemplateInfoRepository.findReportTemplateInfoById(id));
    }

    @Override
    public PageData<ReportTemplateInfo> findReportTemplateInfos(UUID tenantId, ReportTemplateQuery query) {
        log.debug("Try to find report template infos by tenantId [{}] and query [{}]", tenantId, query);
        List<ReportTemplateType> typeList = (query.getTypeList() == null || query.getTypeList().isEmpty()) ? null : query.getTypeList();
        List<TbReportFormat> formatList = (query.getFormatList() == null || query.getFormatList().isEmpty()) ? null : query.getFormatList();
        String searchText = Strings.emptyToNull(query.getPageLink().getTextSearch());
        var pageable = DaoUtil.toPageable(query.getPageLink(), reportTemplateInfoColumnMap);
        PageData<ReportTemplateInfo> result;
        if (query.isIncludeCustomers()) {
            result = DaoUtil.toPageData(reportTemplateInfoRepository.findTenantReportTemplatesIncludingCustomers(
                    tenantId, searchText, typeList, formatList, pageable));
        } else {
            result = DaoUtil.toPageData(reportTemplateInfoRepository.findTenantReportTemplates(
                    tenantId, ModelConstants.NULL_UUID, searchText, typeList, formatList, pageable));
        }
        log.debug("Found [{}] report template infos by tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public List<UUID> findReportTemplateIdsByTenantId(UUID tenantId) {
        log.debug("Try to find report template ids by tenantId [{}]", tenantId);
        return reportTemplateInfoRepository.findIdsByTenantId(tenantId);
    }

    @Override
    public List<UUID> findReportTemplateIdsByTenantIdAndCustomerId(UUID tenantId, UUID customerId) {
        log.debug("Try to find report template ids by tenantId [{}] and customerId [{}]", tenantId, customerId);
        return reportTemplateInfoRepository.findIdsByTenantIdAndCustomerId(tenantId, customerId);
    }

}
