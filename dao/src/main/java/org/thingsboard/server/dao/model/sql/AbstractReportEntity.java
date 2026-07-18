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
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.model.BaseSqlEntity;
import org.thingsboard.server.dao.model.ModelConstants;

import java.util.UUID;

/**
 * Common column mapping shared by {@link ReportEntity} (full row) and {@link ReportInfoEntity}
 * (list projection, with the joined customer title). Both leaf entities map onto the same
 * {@code report} table (mirrors the report_template Entity/InfoEntity split from Task 7).
 *
 * <p>Extends {@link BaseSqlEntity} directly (NOT {@code BaseVersionedEntity}) — the {@code report}
 * table has no {@code version} column: reports are immutable, generated artifacts, never updated.
 *
 * <p>The {@code data} bytea column is intentionally NOT mapped here. It never loads during list/get
 * queries — only via the dedicated native {@code ReportRepository.getDataById}/{@code saveData}
 * queries, keeping every list/find of a {@code Report} cheap regardless of payload size.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@MappedSuperclass
public abstract class AbstractReportEntity<T extends Report> extends BaseSqlEntity<T> {

    @Column(name = ModelConstants.TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ModelConstants.CUSTOMER_ID_PROPERTY)
    private UUID customerId;

    @Column(name = ModelConstants.REPORT_TEMPLATE_ID_PROPERTY)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = ModelConstants.REPORT_FORMAT_PROPERTY)
    private TbReportFormat format;

    @Column(name = ModelConstants.REPORT_NAME_PROPERTY)
    private String name;

    @Column(name = ModelConstants.REPORT_USER_ID_PROPERTY)
    private UUID userId;

    public AbstractReportEntity() {
        super();
    }

    public AbstractReportEntity(T report) {
        super(report);
        if (report.getTenantId() != null) {
            this.tenantId = report.getTenantId().getId();
        }
        if (report.getCustomerId() != null) {
            this.customerId = report.getCustomerId().getId();
        }
        this.templateId = report.getTemplateId();
        this.format = report.getFormat();
        this.name = report.getName();
        if (report.getUserId() != null) {
            this.userId = report.getUserId().getId();
        }
    }

    public AbstractReportEntity(AbstractReportEntity<?> other) {
        super(other);
        this.tenantId = other.getTenantId();
        this.customerId = other.getCustomerId();
        this.templateId = other.getTemplateId();
        this.format = other.getFormat();
        this.name = other.getName();
        this.userId = other.getUserId();
    }

    protected Report toReport() {
        Report report = new Report(new ReportId(id));
        report.setCreatedTime(createdTime);
        if (tenantId != null) {
            report.setTenantId(TenantId.fromUUID(tenantId));
        }
        if (customerId != null) {
            report.setCustomerId(new CustomerId(customerId));
        }
        report.setTemplateId(templateId);
        report.setFormat(format);
        report.setName(name);
        if (userId != null) {
            report.setUserId(new UserId(userId));
        }
        return report;
    }

}
