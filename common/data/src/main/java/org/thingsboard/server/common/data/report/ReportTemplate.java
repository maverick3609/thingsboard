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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.id.ReportTemplateId;

/**
 * Report template entity. Unlike PE, {@code configuration} is a raw {@link JsonNode} tree
 * (jsonb passthrough) rather than a typed {@code ReportTemplateConfig} bean — see the
 * reporting design spec §2.4.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ReportTemplate extends BaseReportTemplate {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode configuration;

    public ReportTemplate() {
        super();
    }

    public ReportTemplate(ReportTemplateId id) {
        super(id);
    }

    public ReportTemplate(BaseReportTemplate reportTemplate) {
        super(reportTemplate);
    }

    public ReportTemplate(ReportTemplate reportTemplate) {
        super(reportTemplate);
        this.configuration = reportTemplate.getConfiguration() != null ? reportTemplate.getConfiguration().deepCopy() : null;
    }

}
