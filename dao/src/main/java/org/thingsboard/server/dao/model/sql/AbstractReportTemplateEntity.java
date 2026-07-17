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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLJsonPGObjectJsonbType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.BaseReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.model.BaseVersionedEntity;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import java.util.UUID;

/**
 * Common column mapping shared by {@link ReportTemplateEntity} (full row, with {@code configuration})
 * and {@link ReportTemplateInfoEntity} (list projection, with the joined customer title). Both leaf
 * entities map onto the same {@code report_template} table (mirrors the scheduler_event Entity/InfoEntity split).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@MappedSuperclass
public abstract class AbstractReportTemplateEntity<T extends BaseReportTemplate> extends BaseVersionedEntity<T> {

    @Column(name = ModelConstants.TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ModelConstants.CUSTOMER_ID_PROPERTY)
    private UUID customerId;

    @Column(name = ModelConstants.REPORT_TEMPLATE_NAME_PROPERTY)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = ModelConstants.REPORT_TEMPLATE_FORMAT_PROPERTY)
    private TbReportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = ModelConstants.REPORT_TEMPLATE_TYPE_PROPERTY)
    private ReportTemplateType type;

    @Column(name = ModelConstants.REPORT_TEMPLATE_DESCRIPTION_PROPERTY)
    private String description;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = ModelConstants.REPORT_TEMPLATE_CONFIGURATION_PROPERTY, columnDefinition = "jsonb")
    private JsonNode configuration;

    @Convert(converter = JsonConverter.class)
    @Column(name = ModelConstants.ADDITIONAL_INFO_PROPERTY)
    private JsonNode additionalInfo;

    @Column(name = ModelConstants.EXTERNAL_ID_PROPERTY)
    private UUID externalId;

    public AbstractReportTemplateEntity() {
        super();
    }

    public AbstractReportTemplateEntity(T reportTemplate) {
        super(reportTemplate);
        if (reportTemplate.getTenantId() != null) {
            this.tenantId = reportTemplate.getTenantId().getId();
        }
        if (reportTemplate.getCustomerId() != null) {
            this.customerId = reportTemplate.getCustomerId().getId();
        }
        this.name = reportTemplate.getName();
        this.format = reportTemplate.getFormat();
        this.type = reportTemplate.getType();
        this.description = reportTemplate.getDescription();
        this.additionalInfo = reportTemplate.getAdditionalInfo();
        this.externalId = getUuid(reportTemplate.getExternalId());
    }

    public AbstractReportTemplateEntity(AbstractReportTemplateEntity<?> other) {
        super(other);
        this.tenantId = other.getTenantId();
        this.customerId = other.getCustomerId();
        this.name = other.getName();
        this.format = other.getFormat();
        this.type = other.getType();
        this.description = other.getDescription();
        this.configuration = other.getConfiguration();
        this.additionalInfo = other.getAdditionalInfo();
        this.externalId = other.getExternalId();
    }

    protected BaseReportTemplate toBaseReportTemplate() {
        BaseReportTemplate reportTemplate = new BaseReportTemplate(new ReportTemplateId(id));
        reportTemplate.setCreatedTime(createdTime);
        if (tenantId != null) {
            reportTemplate.setTenantId(TenantId.fromUUID(tenantId));
        }
        if (customerId != null) {
            reportTemplate.setCustomerId(new CustomerId(customerId));
        }
        reportTemplate.setName(name);
        reportTemplate.setFormat(format);
        reportTemplate.setType(type);
        reportTemplate.setDescription(description);
        reportTemplate.setAdditionalInfo(additionalInfo);
        reportTemplate.setExternalId(getEntityId(externalId, ReportTemplateId::new));
        reportTemplate.setVersion(version);
        return reportTemplate;
    }

}
