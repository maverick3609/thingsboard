// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.ReportTemplateEntity;
import org.thingsboard.server.dao.report.ReportTemplateDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@SqlDao
@Slf4j
public class JpaReportTemplateDao extends JpaAbstractDao<ReportTemplateEntity, ReportTemplate> implements ReportTemplateDao {

    @Autowired
    private ReportTemplateRepository reportTemplateRepository;

    @Override
    protected Class<ReportTemplateEntity> getEntityClass() {
        return ReportTemplateEntity.class;
    }

    @Override
    protected JpaRepository<ReportTemplateEntity, UUID> getRepository() {
        return reportTemplateRepository;
    }

    @Override
    public Long countByTenantId(TenantId tenantId) {
        return reportTemplateRepository.countByTenantId(tenantId.getId());
    }

    @Override
    public PageData<ReportTemplate> findAllByTenantId(TenantId tenantId, PageLink pageLink) {
        return findByTenantId(tenantId.getId(), pageLink);
    }

    @Override
    public PageData<ReportTemplate> findByTenantId(UUID tenantId, PageLink pageLink) {
        log.debug("Try to find report templates by tenantId [{}] and pageLink [{}]", tenantId, pageLink);
        PageData<ReportTemplate> result = DaoUtil.toPageData(reportTemplateRepository.findByTenantId(tenantId, DaoUtil.toPageable(pageLink)));
        log.debug("Found [{}] report templates by tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public ReportTemplate findByTenantIdAndExternalId(UUID tenantId, UUID externalId) {
        return DaoUtil.getData(reportTemplateRepository.findByTenantIdAndExternalId(tenantId, externalId));
    }

    @Override
    public ReportTemplateId getExternalIdByInternal(ReportTemplateId internalId) {
        return Optional.ofNullable(reportTemplateRepository.getExternalIdById(internalId.getId()))
                .map(ReportTemplateId::new).orElse(null);
    }

    @Override
    public List<ReportTemplate> findReportTemplatesByIds(UUID tenantId, List<UUID> reportTemplateIds) {
        log.debug("Try to find report templates by tenantId [{}] and ids [{}]", tenantId, reportTemplateIds);
        return DaoUtil.convertDataList(reportTemplateRepository.findByTenantIdAndIdIn(tenantId, reportTemplateIds));
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.REPORT_TEMPLATE;
    }

}
