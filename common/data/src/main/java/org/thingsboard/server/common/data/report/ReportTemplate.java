// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;

/**
 * Report template entity. As of R2a {@code configuration} is the typed
 * {@link ReportTemplateConfig} bean (PE shape, design spec §2.4 / R2a §4); the DB column stays
 * jsonb — the entity mapping ({@code AbstractReportTemplateEntity}) tree-maps between this typed
 * field and the jsonb {@link JsonNode} column.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ReportTemplate extends BaseReportTemplate {

    // common/data does not depend on common/util (JacksonUtil), so clone via a local mapper.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ReportTemplateConfig configuration;

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
        this.configuration = deepCopyConfiguration(reportTemplate.getConfiguration());
    }

    /**
     * Typed clone of the polymorphic {@link ReportTemplateConfig}. Goes through the JSON tree
     * ({@code valueToTree} → {@code treeToValue}) rather than {@code writeValueAsString}: PE's
     * {@code @JsonTypeInfo(As.PROPERTY, property="format")} co-exists with a real {@code getFormat()},
     * so a direct string serialization emits {@code format} twice — the tree path collapses it to a
     * single key (last-wins) before reading the polymorphic root back.
     */
    private static ReportTemplateConfig deepCopyConfiguration(ReportTemplateConfig configuration) {
        if (configuration == null) {
            return null;
        }
        try {
            return MAPPER.treeToValue(MAPPER.valueToTree(configuration), ReportTemplateConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deep-copy report template configuration", e);
        }
    }

}
