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

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;

import static org.thingsboard.server.dao.model.ModelConstants.REPORT_TEMPLATE_TABLE_NAME;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = REPORT_TEMPLATE_TABLE_NAME)
public final class ReportTemplateEntity extends AbstractReportTemplateEntity<ReportTemplate> {

    public ReportTemplateEntity() {
        super();
    }

    public ReportTemplateEntity(ReportTemplate reportTemplate) {
        super(reportTemplate);
        // Domain is the typed ReportTemplateConfig; the column stays jsonb (JsonNode). valueToTree
        // (not writeValueAsString) collapses PE's duplicate `format` key — @JsonTypeInfo(As.PROPERTY,
        // property="format") plus the real getFormat() — into a single key before it hits jsonb.
        this.setConfiguration(reportTemplate.getConfiguration() != null
                ? JacksonUtil.valueToTree(reportTemplate.getConfiguration()) : null);
    }

    public ReportTemplate toData() {
        ReportTemplate reportTemplate = new ReportTemplate(toBaseReportTemplate());
        reportTemplate.setConfiguration(getConfiguration() != null
                ? JacksonUtil.treeToValue(getConfiguration(), ReportTemplateConfig.class) : null);
        return reportTemplate;
    }

}
