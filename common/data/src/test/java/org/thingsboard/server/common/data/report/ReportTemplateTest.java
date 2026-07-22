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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;

import static org.assertj.core.api.Assertions.assertThat;

// JacksonUtil lives in common/util, which is not a common/data dependency;
// use a plain ObjectMapper here per the task brief's documented fallback.
class ReportTemplateTest {

    // R2a: configuration is the typed ReportTemplateConfig (PE shape). Root keyed on `format`; the
    // DASHBOARD component nests its settings under `config`; unknown keys are dropped by the model's
    // @JsonIgnoreProperties(ignoreUnknown=true) rather than surviving as an untyped tree.
    @Test
    void typedConfigurationRoundTripsAndBindsNestedDashboard() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReportTemplateConfig cfg = mapper.readValue(
                "{\"format\":\"PDF\",\"components\":[" +
                        "{\"type\":\"DASHBOARD\",\"config\":{\"dashboardId\":\"d1\",\"state\":\"main\"},\"futureKey\":true}]}",
                ReportTemplateConfig.class);

        ReportTemplate t = new ReportTemplate();
        t.setName("Weekly");
        t.setFormat(TbReportFormat.PDF);
        t.setType(ReportTemplateType.REPORT);
        t.setConfiguration(cfg);

        ReportTemplate back = mapper.readValue(mapper.writeValueAsString(t), ReportTemplate.class);

        assertThat(back.getType()).isEqualTo(ReportTemplateType.REPORT);
        assertThat(back.getConfiguration()).isInstanceOf(PdfReportTemplateConfig.class);
        assertThat(back.getConfiguration().getFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(back.getConfiguration().getComponents()).hasSize(1);
        assertThat(back.getConfiguration().getComponents().get(0).getType()).isEqualTo(ReportComponentType.DASHBOARD);
        DashboardComponent dashboard = (DashboardComponent) back.getConfiguration().getComponents().get(0);
        assertThat(dashboard.getConfig().getDashboardId()).isEqualTo("d1");
        assertThat(dashboard.getConfig().getState()).isEqualTo("main");
    }

    // The typed deepCopy (ReportTemplate copy ctor) goes through valueToTree/treeToValue, which
    // collapses PE's duplicate `format` key; a naive writeValueAsString would emit it twice.
    @Test
    void copyConstructorDeepCopiesTypedConfiguration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReportTemplateConfig cfg = mapper.readValue(
                "{\"format\":\"PDF\",\"components\":[{\"type\":\"DASHBOARD\",\"config\":{\"dashboardId\":\"d1\"}}]}",
                ReportTemplateConfig.class);
        ReportTemplate original = new ReportTemplate();
        original.setName("Weekly");
        original.setConfiguration(cfg);

        ReportTemplate copy = new ReportTemplate(original);

        assertThat(copy.getConfiguration()).isNotSameAs(original.getConfiguration());
        // structural (tree) equality — the ported component model's Lombok equals chain is not a
        // reliable deep-equals across instances, so compare the reserialized JSON instead.
        JsonNode copyTree = mapper.valueToTree(copy.getConfiguration());
        JsonNode originalTree = mapper.valueToTree(original.getConfiguration());
        assertThat(copyTree).isEqualTo(originalTree);
        DashboardComponent dashboard = (DashboardComponent) copy.getConfiguration().getComponents().get(0);
        assertThat(dashboard.getConfig().getDashboardId()).isEqualTo("d1");
    }
}
