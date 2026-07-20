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
package org.thingsboard.server.common.data.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ExportableEntity;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.HasVersion;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.validation.Length;
import org.thingsboard.server.common.data.validation.NoXss;

/**
 * Common fields shared by {@link ReportTemplate} (full entity, with configuration) and
 * {@link ReportTemplateInfo} (list projection, with the owning customer's title).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class BaseReportTemplate extends BaseDataWithAdditionalInfo<ReportTemplateId>
        implements HasName, HasTenantId, HasCustomerId, HasVersion, ExportableEntity<ReportTemplateId> {

    private TenantId tenantId;
    private CustomerId customerId;
    @NoXss
    @Length(fieldName = "name")
    private String name;
    private TbReportFormat format;
    private ReportTemplateType type;
    @NoXss
    @Length(fieldName = "description", max = 1024)
    private String description;
    private ReportTemplateId externalId;
    private Long version;

    public BaseReportTemplate() {
        super();
    }

    public BaseReportTemplate(ReportTemplateId id) {
        super(id);
    }

    public BaseReportTemplate(BaseReportTemplate reportTemplate) {
        super(reportTemplate);
        this.tenantId = reportTemplate.getTenantId();
        this.customerId = reportTemplate.getCustomerId();
        this.name = reportTemplate.getName();
        this.format = reportTemplate.getFormat();
        this.type = reportTemplate.getType();
        this.description = reportTemplate.getDescription();
        this.externalId = reportTemplate.getExternalId();
        this.version = reportTemplate.getVersion();
    }

    @JsonIgnore
    public EntityType getEntityType() {
        return EntityType.REPORT_TEMPLATE;
    }

}
