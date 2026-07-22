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
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.DividerComponent;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ErrorComponent;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.components.PageBreakComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.RichTextComponent;
import org.thingsboard.server.common.data.report.configuration.components.SubReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentPolymorphismJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ReportComponent bind(String json) throws Exception {
        return mapper.readValue(json, ReportComponent.class);
    }

    @Test
    void eachTypeDiscriminatorBindsToItsClass() throws Exception {
        assertThat(bind("{\"type\":\"HEADING\"}")).isInstanceOf(HeadingComponent.class);
        assertThat(bind("{\"type\":\"RICH_TEXT\"}")).isInstanceOf(RichTextComponent.class);
        assertThat(bind("{\"type\":\"ENTITY_TABLE\"}")).isInstanceOf(EntityTableComponent.class);
        assertThat(bind("{\"type\":\"TIME_SERIES_TABLE\"}")).isInstanceOf(TimeseriesTableComponent.class);
        assertThat(bind("{\"type\":\"ALARM_TABLE\"}")).isInstanceOf(AlarmTableComponent.class);
        assertThat(bind("{\"type\":\"DASHBOARD\"}")).isInstanceOf(DashboardComponent.class);
        assertThat(bind("{\"type\":\"IMAGE\"}")).isInstanceOf(ImageComponent.class);
        assertThat(bind("{\"type\":\"SUB_REPORT\"}")).isInstanceOf(SubReportComponent.class);
        assertThat(bind("{\"type\":\"PAGE_BREAK\"}")).isInstanceOf(PageBreakComponent.class);
        assertThat(bind("{\"type\":\"ERROR\"}")).isInstanceOf(ErrorComponent.class);
        assertThat(bind("{\"type\":\"DIVIDER\"}")).isInstanceOf(DividerComponent.class);
    }

    @Test
    void typeSerializesFromGetType() throws Exception {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Title");
        String json = mapper.writeValueAsString(heading);
        assertThat(json).contains("\"type\":\"HEADING\"");
        ReportComponent restored = bind(json);
        assertThat(restored).isInstanceOf(HeadingComponent.class);
        assertThat(restored.getType()).isEqualTo(ReportComponentType.HEADING);
    }

    @Test
    void nestedDashboardConfigBinds() throws Exception {
        String json = "{\"type\":\"DASHBOARD\",\"config\":{\"dashboardId\":\"dash-1\",\"state\":\"main\"}}";
        ReportComponent component = bind(json);
        assertThat(component).isInstanceOf(DashboardComponent.class);
        DashboardComponent dashboard = (DashboardComponent) component;
        assertThat(dashboard.getConfig()).isNotNull();
        assertThat(dashboard.getConfig().getDashboardId()).isEqualTo("dash-1");
        assertThat(dashboard.getConfig().getState()).isEqualTo("main");
    }

    @Test
    void inheritedLayoutFieldsBindAndRoundTrip() throws Exception {
        ImageComponent image = new ImageComponent();
        image.setSourceType(ImageSourceType.IMAGE);
        image.setImageUrl("tb-image;/api/images/x");
        image.setCustomWidth(320);
        image.setBackground("#ffffff");
        image.setBorderWidth(2);

        String json = mapper.writeValueAsString(image);
        ImageComponent restored = (ImageComponent) bind(json);
        assertThat(restored.getBackground()).isEqualTo("#ffffff");
        assertThat(restored.getBorderWidth()).isEqualTo(2);
        assertThat(restored.getCustomWidth()).isEqualTo(320);
        assertThat(restored).isEqualTo(image);
    }

    @Test
    void errorComponentExceptionIsNotSerialized() throws Exception {
        ErrorComponent error = new ErrorComponent("boom", new IllegalStateException("cause"));
        String json = mapper.writeValueAsString(error);
        assertThat(json).contains("\"errorMessage\":\"boom\"");
        assertThat(json).doesNotContain("cause");
        ErrorComponent restored = (ErrorComponent) bind(json);
        assertThat(restored.getErrorMessage()).isEqualTo("boom");
        assertThat(restored.getException()).isNull();
    }

    @Test
    void unknownPropertyToleratedOnComponent() throws Exception {
        ReportComponent component = bind("{\"type\":\"RICH_TEXT\",\"value\":\"hi\",\"bogus\":1}");
        assertThat(component).isInstanceOf(RichTextComponent.class);
        assertThat(((RichTextComponent) component).getValue()).isEqualTo("hi");
    }
}
