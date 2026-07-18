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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.ReportInfoQuery;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.service.Validator;
import org.thingsboard.server.dao.service.validator.ReportDataValidator;

import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultReportService extends AbstractEntityService implements ReportService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_REPORT_ID = "Incorrect reportId ";

    private final ReportDao reportDao;
    private final ReportDataValidator reportDataValidator;

    @Override
    public Report createReport(Report report, byte[] data) {
        log.debug("Executing createReport, tenantId [{}], name [{}]", report.getTenantId(), report.getName());
        reportDataValidator.validate(report, Report::getTenantId);
        reportDataValidator.validateReportSize(report.getTenantId(), data);
        Report savedReport = reportDao.save(report.getTenantId(), report);
        reportDao.saveData(savedReport.getTenantId(), savedReport.getId(), data);
        log.info("Created report, tenantId [{}], id [{}], name [{}]", savedReport.getTenantId(), savedReport.getId(), savedReport.getName());
        return savedReport;
    }

    @Override
    public Report findReportById(TenantId tenantId, ReportId reportId) {
        log.debug("Executing findReportById, tenantId [{}], reportId [{}]", tenantId, reportId);
        Validator.validateId(reportId, id -> INCORRECT_REPORT_ID + id);
        return reportDao.findById(tenantId, reportId.getId());
    }

    @Override
    public byte[] getReportData(TenantId tenantId, ReportId reportId) {
        log.debug("Executing getReportData, tenantId [{}], reportId [{}]", tenantId, reportId);
        Validator.validateId(reportId, id -> INCORRECT_REPORT_ID + id);
        return reportDao.getData(tenantId, reportId);
    }

    @Override
    public ReportInfo findReportInfoById(TenantId tenantId, ReportId reportId) {
        log.debug("Executing findReportInfoById, tenantId [{}], reportId [{}]", tenantId, reportId);
        Validator.validateId(reportId, id -> INCORRECT_REPORT_ID + id);
        return reportDao.findInfoById(tenantId, reportId.getId());
    }

    @Override
    public PageData<ReportInfo> findReports(TenantId tenantId, ReportInfoQuery query) {
        log.debug("Executing findReports, tenantId [{}], query [{}]", tenantId, query);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validatePageLink(query.getPageLink());
        PageData<ReportInfo> result = reportDao.findReports(tenantId, query);
        log.debug("Found [{}] reports, tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public void deleteReport(TenantId tenantId, ReportId reportId) {
        log.debug("Executing deleteReport, tenantId [{}], reportId [{}]", tenantId, reportId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validateId(reportId, id -> INCORRECT_REPORT_ID + id);
        deleteEntity(tenantId, reportId, false);
    }

    @Override
    public void deleteEntity(TenantId tenantId, EntityId id, boolean force) {
        reportDao.removeById(tenantId, id.getId());
        log.info("Deleted report, tenantId [{}], id [{}]", tenantId, id.getId());
    }

    @Override
    public void deleteReportsByTenantId(TenantId tenantId) {
        log.debug("Executing deleteReportsByTenantId, tenantId [{}]", tenantId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        reportDao.deleteByTenantId(tenantId);
        log.info("Deleted all reports, tenantId [{}]", tenantId);
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        deleteReportsByTenantId(tenantId);
    }

    @Override
    public void deleteReportsByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId) {
        log.debug("Executing deleteReportsByTenantIdAndCustomerId, tenantId [{}], customerId [{}]", tenantId, customerId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validateId(customerId, id -> INCORRECT_CUSTOMER_ID + id);
        reportDao.deleteByTenantIdAndCustomerId(tenantId, customerId);
        log.info("Deleted reports, tenantId [{}], customerId [{}]", tenantId, customerId);
    }

    @Override
    public Optional<HasId<?>> findEntity(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(findReportById(tenantId, new ReportId(entityId.getId())));
    }

    @Override
    public FluentFuture<Optional<HasId<?>>> findEntityAsync(TenantId tenantId, EntityId entityId) {
        return FluentFuture.from(reportDao.findByIdAsync(tenantId, entityId.getId()))
                .transform(Optional::ofNullable, directExecutor());
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.REPORT;
    }

}
