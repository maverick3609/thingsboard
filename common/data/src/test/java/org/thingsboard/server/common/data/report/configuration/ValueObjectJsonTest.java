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
import org.thingsboard.server.common.data.kv.IntervalType;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.style.DataKeySettingsType;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.Insets;
import org.thingsboard.server.common.data.report.configuration.timewindow.History;
import org.thingsboard.server.common.data.report.configuration.timewindow.Interval;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueObjectJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        String json = mapper.writeValueAsString(value);
        return mapper.readValue(json, type);
    }

    @Test
    void insetsRoundTrips() throws Exception {
        Insets insets = new Insets(1, 2, 3, 4);
        assertThat(roundTrip(insets, Insets.class)).isEqualTo(insets);
    }

    @Test
    void fontRoundTrips() throws Exception {
        Font font = new Font();
        font.setSize(12.5f);
        font.setWeight(FontWeight.BOLD);
        font.setStyle(FontStyle.ITALIC);
        font.setFamily("Roboto");
        assertThat(roundTrip(font, Font.class)).isEqualTo(font);
    }

    @Test
    void dataSourceRoundTripsWithLabelEnum() throws Exception {
        DataSource ds = DataSource.builder()
                .type(DataSourceType.DEVICE)
                .deviceId("dev-1")
                .dataKeys(List.of(new DataKey("temperature", "timeseries", "Temp")))
                .build();
        String json = mapper.writeValueAsString(ds);
        assertThat(json).contains("\"type\":\"device\"");
        assertThat(roundTrip(ds, DataSource.class)).isEqualTo(ds);
    }

    @Test
    void dataKeySettingsDefaultsToDefaultWhenTypeMissing() throws Exception {
        DataKeySettings settings = mapper.readValue("{\"columnWidth\":\"120px\"}", DataKeySettings.class);
        assertThat(settings).isInstanceOf(DefaultDataKeySettings.class);
        assertThat(settings.getType()).isEqualTo(DataKeySettingsType.DEFAULT);
    }

    @Test
    void dataKeySettingsBindsColumnByType() throws Exception {
        DataKeySettings settings = mapper.readValue("{\"type\":\"COLUMN\",\"columnWidth\":\"120px\"}", DataKeySettings.class);
        assertThat(settings).isInstanceOf(ColumnSettings.class);
        assertThat(settings.getType()).isEqualTo(DataKeySettingsType.COLUMN);
        assertThat(((ColumnSettings) settings).getColumnWidth()).isEqualTo("120px");
    }

    @Test
    void intervalCodecRoundTripsMillisAsNumber() throws Exception {
        Interval interval = Interval.of(60000L);
        String json = mapper.writeValueAsString(interval);
        assertThat(json).isEqualTo("60000");
        assertThat(roundTrip(interval, Interval.class)).isEqualTo(interval);
    }

    @Test
    void intervalCodecRoundTripsNamedTypeAsString() throws Exception {
        Interval interval = Interval.of(IntervalType.WEEK);
        String json = mapper.writeValueAsString(interval);
        assertThat(json).isEqualTo("\"WEEK\"");
        assertThat(roundTrip(interval, Interval.class)).isEqualTo(interval);
    }

    @Test
    void historyIntervalRoundTripsThroughCustomCodec() throws Exception {
        History history = new History();
        history.setHistoryType(1);
        history.setInterval(Interval.of(IntervalType.MONTH));
        history.setTimewindowMs(3600000L);
        History restored = roundTrip(history, History.class);
        assertThat(restored.getInterval().getIntervalType()).isEqualTo(IntervalType.MONTH);
        assertThat(restored).isEqualTo(history);
    }

    @Test
    void headerFooterRecursiveFirstPageRoundTrips() throws Exception {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Page header");

        HeaderFooter firstPage = new HeaderFooter();
        firstPage.setEnabled(true);
        firstPage.setComponents(List.of(new HeadingComponent()));

        HeaderFooter header = new HeaderFooter();
        header.setEnabled(true);
        header.setComponents(List.<ReportComponent>of(heading));
        header.setFirstPage(firstPage);

        HeaderFooter restored = roundTrip(header, HeaderFooter.class);
        assertThat(restored.getFirstPage()).isNotNull();
        assertThat(restored.getFirstPage().isEnabled()).isTrue();
        assertThat(restored).isEqualTo(header);
    }

    @Test
    void unknownPropertyToleratedOnValueObject() throws Exception {
        Font font = mapper.readValue("{\"size\":10.0,\"family\":\"Roboto\",\"bogus\":\"x\"}", Font.class);
        assertThat(font.getFamily()).isEqualTo("Roboto");
    }
}
