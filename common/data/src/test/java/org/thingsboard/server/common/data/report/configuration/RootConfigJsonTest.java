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
package org.thingsboard.server.common.data.report.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.PageBreakComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.style.PageOrientation;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RootConfigJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pdfFormatDispatchesToPdfConfig() throws Exception {
        ReportTemplateConfig config = mapper.readValue("{\"format\":\"PDF\",\"pageSize\":\"A4\"}", ReportTemplateConfig.class);
        assertThat(config).isInstanceOf(PdfReportTemplateConfig.class);
        assertThat(config.getFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(((PdfReportTemplateConfig) config).getPageSize()).isEqualTo(PageSize.A4);
    }

    @Test
    void csvFormatDispatchesToCsvConfig() throws Exception {
        ReportTemplateConfig config = mapper.readValue("{\"format\":\"CSV\",\"namePattern\":\"report\"}", ReportTemplateConfig.class);
        assertThat(config).isInstanceOf(CsvReportTemplateConfig.class);
        assertThat(config.getFormat()).isEqualTo(TbReportFormat.CSV);
        assertThat(config.getNamePattern()).isEqualTo("report");
    }

    @Test
    void formatDiscriminatorSerializesAndRoundTrips() throws Exception {
        // PE keeps @JsonTypeInfo(As.PROPERTY, property="format") alongside a real getFormat()
        // getter; on direct string serialization Jackson therefore emits the "format" key from
        // both the type-id and the bean property. This is PE-verbatim behavior — the duplicate
        // collapses to a single key when the typed config is routed through valueToTree/jsonb
        // (A5) or re-read (last-wins). We assert the discriminator is present and dispatches.
        PdfReportTemplateConfig config = PdfReportTemplateConfig.builder()
                .namePattern("Daily report")
                .pageSize(PageSize.LETTER)
                .pageOrientation(PageOrientation.LANDSCAPE)
                .build();
        String json = mapper.writeValueAsString(config);
        assertThat(json).contains("\"format\":\"PDF\"");

        ReportTemplateConfig restored = mapper.readValue(json, ReportTemplateConfig.class);
        assertThat(restored).isInstanceOf(PdfReportTemplateConfig.class);
        assertThat(restored.getFormat()).isEqualTo(TbReportFormat.PDF);

        // Through the tree (the A5 entity-mapping path) the key is single-valued.
        String viaTree = mapper.writeValueAsString(mapper.valueToTree(config));
        assertThat(viaTree.split("\"format\"", -1).length - 1).isEqualTo(1);
    }

    @Test
    void fullPdfConfigWithComponentsRoundTrips() throws Exception {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Section 1");

        PdfReportTemplateConfig config = PdfReportTemplateConfig.builder()
                .namePattern("Daily report")
                .timeDataPattern("yyyy-MM-dd")
                .pageSize(PageSize.A4)
                .pageOrientation(PageOrientation.PORTRAIT)
                .components(List.<ReportComponent>of(heading, new PageBreakComponent()))
                .build();

        String json = mapper.writeValueAsString(config);
        ReportTemplateConfig restored = mapper.readValue(json, ReportTemplateConfig.class);

        assertThat(restored).isInstanceOf(PdfReportTemplateConfig.class);
        assertThat(restored.getComponents()).hasSize(2);
        assertThat(restored.getComponents().get(0)).isInstanceOf(HeadingComponent.class);
        assertThat(restored.getComponents().get(1)).isInstanceOf(PageBreakComponent.class);
        assertThat(((PdfReportTemplateConfig) restored).getPageSize()).isEqualTo(PageSize.A4);
    }

    @Test
    void unknownRootPropertyTolerated() throws Exception {
        ReportTemplateConfig config = mapper.readValue("{\"format\":\"PDF\",\"bogus\":true}", ReportTemplateConfig.class);
        assertThat(config).isInstanceOf(PdfReportTemplateConfig.class);
    }
}
