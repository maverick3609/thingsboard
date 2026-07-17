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

import com.google.common.util.concurrent.FluentFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.entity.EntityCountService;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.Validator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

@Service("ReportTemplateDaoService")
@Slf4j
public class BaseReportTemplateService extends AbstractEntityService implements ReportTemplateService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_REPORT_TEMPLATE_ID = "Incorrect reportTemplateId ";

    @Autowired
    private ReportTemplateDao reportTemplateDao;
    @Autowired
    private ReportTemplateInfoDao reportTemplateInfoDao;
    @Autowired
    private DataValidator<ReportTemplate> reportTemplateDataValidator;
    @Autowired
    private EntityCountService entityCountService;

    @Override
    public ReportTemplate findReportTemplateById(TenantId tenantId, ReportTemplateId reportTemplateId) {
        log.debug("Executing findReportTemplateById, tenantId [{}], reportTemplateId [{}]", tenantId, reportTemplateId);
        Validator.validateId(reportTemplateId, id -> INCORRECT_REPORT_TEMPLATE_ID + id);
        return reportTemplateDao.findById(tenantId, reportTemplateId.getId());
    }

    @Override
    public ReportTemplateInfo findReportTemplateInfoById(TenantId tenantId, ReportTemplateId reportTemplateId) {
        log.debug("Executing findReportTemplateInfoById, tenantId [{}], reportTemplateId [{}]", tenantId, reportTemplateId);
        Validator.validateId(reportTemplateId, id -> INCORRECT_REPORT_TEMPLATE_ID + id);
        return reportTemplateInfoDao.findById(tenantId, reportTemplateId.getId());
    }

    @Override
    public PageData<ReportTemplateInfo> findReportTemplateInfos(TenantId tenantId, ReportTemplateQuery query) {
        log.debug("Executing findReportTemplateInfos, tenantId [{}], query [{}]", tenantId, query);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validatePageLink(query.getPageLink());
        PageData<ReportTemplateInfo> result = reportTemplateInfoDao.findReportTemplateInfos(tenantId.getId(), query);
        log.debug("Found [{}] report templates, tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public List<ReportTemplate> findReportTemplatesByIds(TenantId tenantId, List<ReportTemplateId> reportTemplateIds) {
        log.debug("Executing findReportTemplatesByIds, tenantId [{}], reportTemplateIds [{}]", tenantId, reportTemplateIds);
        Validator.validateIds(reportTemplateIds, ids -> "Incorrect reportTemplateIds " + ids);
        List<ReportTemplate> result = reportTemplateDao.findReportTemplatesByIds(tenantId.getId(), DaoUtil.toUUIDs(reportTemplateIds));
        log.debug("Found [{}] report templates, tenantId [{}]", result.size(), tenantId);
        return result;
    }

    @Override
    public ReportTemplate saveReportTemplate(ReportTemplate reportTemplate) {
        log.debug("Executing saveReportTemplate, tenantId [{}], name [{}]", reportTemplate.getTenantId(), reportTemplate.getName());
        reportTemplateDataValidator.validate(reportTemplate, ReportTemplate::getTenantId);
        try {
            boolean created = reportTemplate.getId() == null;
            ReportTemplate savedReportTemplate = reportTemplateDao.save(reportTemplate.getTenantId(), reportTemplate);
            if (created) {
                entityCountService.publishCountEntityEvictEvent(savedReportTemplate.getTenantId(), EntityType.REPORT_TEMPLATE);
            }
            eventPublisher.publishEvent(SaveEntityEvent.builder().tenantId(savedReportTemplate.getTenantId()).entityId(savedReportTemplate.getId())
                    .entity(savedReportTemplate).created(created).build());
            log.info("Saved report template, tenantId [{}], id [{}], name [{}]", savedReportTemplate.getTenantId(), savedReportTemplate.getId(), savedReportTemplate.getName());
            return savedReportTemplate;
        } catch (Exception e) {
            log.warn("Failed to save report template, tenantId [{}], name [{}]", reportTemplate.getTenantId(), reportTemplate.getName());
            checkConstraintViolation(e, "report_template_external_id_unq_key", "Report template with such external id already exists!");
            throw e;
        }
    }

    @Override
    public void deleteReportTemplate(TenantId tenantId, ReportTemplateId reportTemplateId) {
        log.debug("Executing deleteReportTemplate, tenantId [{}], reportTemplateId [{}]", tenantId, reportTemplateId);
        Validator.validateId(reportTemplateId, id -> INCORRECT_REPORT_TEMPLATE_ID + id);
        ReportTemplate reportTemplate = findReportTemplateById(tenantId, reportTemplateId);
        if (reportTemplate == null) {
            return;
        }
        reportTemplateDao.removeById(tenantId, reportTemplateId.getId());
        entityCountService.publishCountEntityEvictEvent(tenantId, EntityType.REPORT_TEMPLATE);
        eventPublisher.publishEvent(DeleteEntityEvent.builder().tenantId(tenantId).entityId(reportTemplateId).entity(reportTemplate).build());
        log.info("Deleted report template, tenantId [{}], id [{}], name [{}]", tenantId, reportTemplateId, reportTemplate.getName());
    }

    @Override
    public void deleteEntity(TenantId tenantId, EntityId id, boolean force) {
        deleteReportTemplate(tenantId, (ReportTemplateId) id);
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        log.debug("Executing deleteByTenantId, tenantId [{}]", tenantId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        List<UUID> ids = reportTemplateInfoDao.findReportTemplateIdsByTenantId(tenantId.getId());
        for (UUID id : ids) {
            deleteReportTemplate(tenantId, new ReportTemplateId(id));
        }
    }

    @Override
    public void deleteByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId) {
        log.debug("Executing deleteByTenantIdAndCustomerId, tenantId [{}], customerId [{}]", tenantId, customerId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validateId(customerId, id -> INCORRECT_CUSTOMER_ID + id);
        List<UUID> ids = reportTemplateInfoDao.findReportTemplateIdsByTenantIdAndCustomerId(tenantId.getId(), customerId.getId());
        for (UUID id : ids) {
            deleteReportTemplate(tenantId, new ReportTemplateId(id));
        }
    }

    @Override
    public Optional<HasId<?>> findEntity(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(findReportTemplateById(tenantId, new ReportTemplateId(entityId.getId())));
    }

    @Override
    public FluentFuture<Optional<HasId<?>>> findEntityAsync(TenantId tenantId, EntityId entityId) {
        return FluentFuture.from(reportTemplateDao.findByIdAsync(tenantId, entityId.getId()))
                .transform(Optional::ofNullable, directExecutor());
    }

    @Override
    public long countByTenantId(TenantId tenantId) {
        return reportTemplateDao.countByTenantId(tenantId);
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.REPORT_TEMPLATE;
    }

}
