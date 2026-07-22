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
import org.thingsboard.server.common.data.report.configuration.image.ImageAlignment;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.common.data.report.configuration.image.ImageWidthType;
import org.thingsboard.server.common.data.report.configuration.style.BorderLength;
import org.thingsboard.server.common.data.report.configuration.style.BorderType;
import org.thingsboard.server.common.data.report.configuration.style.DataKeySettingsType;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.PageOrientation;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;
import org.thingsboard.server.common.data.report.configuration.timewindow.QuickTimeInterval;

import static org.assertj.core.api.Assertions.assertThat;

class EnumJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T extends Enum<T>> void assertLabel(T value, String label, Class<T> type) throws Exception {
        assertThat(mapper.writeValueAsString(value)).isEqualTo('"' + label + '"');
        assertThat(mapper.readValue('"' + label + '"', type)).isEqualTo(value);
    }

    private <T extends Enum<T>> void assertName(T value, Class<T> type) throws Exception {
        assertThat(mapper.writeValueAsString(value)).isEqualTo('"' + value.name() + '"');
        assertThat(mapper.readValue('"' + value.name() + '"', type)).isEqualTo(value);
    }

    @Test
    void labelBasedEnumsUsePeLabels() throws Exception {
        assertLabel(DataSourceType.DEVICE, "device", DataSourceType.class);
        assertLabel(DataSourceType.ENTITY_COUNT, "entityCount", DataSourceType.class);
        assertLabel(DataSourceType.ALARM_COUNT, "alarmCount", DataSourceType.class);

        assertLabel(FontStyle.NORMAL, "normal", FontStyle.class);
        assertLabel(FontStyle.ITALIC, "italic", FontStyle.class);

        assertLabel(FontWeight.BOLD, "bold", FontWeight.class);
        assertLabel(FontWeight.WEIGHT_500, "500", FontWeight.class);

        assertLabel(TextAlignment.JUSTIFY, "justify", TextAlignment.class);
        assertLabel(VerticalAlignment.MIDDLE, "middle", VerticalAlignment.class);
        assertLabel(BorderType.DASHED, "dashed", BorderType.class);

        assertLabel(ImageAlignment.CENTER, "center", ImageAlignment.class);
        assertLabel(ImageSourceType.ENTITY_KEY, "entityKey", ImageSourceType.class);
        assertLabel(ImageWidthType.FIT_WIDTH, "fitWidth", ImageWidthType.class);
    }

    @Test
    void labelDeserializationIsCaseInsensitive() throws Exception {
        assertThat(mapper.readValue("\"DEVICE\"", DataSourceType.class)).isEqualTo(DataSourceType.DEVICE);
    }

    @Test
    void nameBasedEnumsUseName() throws Exception {
        assertName(BorderLength.SHORT, BorderLength.class);
        assertName(PageOrientation.LANDSCAPE, PageOrientation.class);
        assertName(PageSize.LETTER, PageSize.class);
        assertName(DataKeySettingsType.COLUMN, DataKeySettingsType.class);
        assertName(QuickTimeInterval.CURRENT_WEEK_ISO_SO_FAR, QuickTimeInterval.class);
    }

    @Test
    void pageSizeCarriesDimensions() {
        assertThat(PageSize.A4.getWidth()).isEqualTo(595);
        assertThat(PageSize.A4.getHeight()).isEqualTo(842);
        assertThat(PageSize.TABLOID.getWidth()).isEqualTo(792);
        assertThat(PageSize.TABLOID.getHeight()).isEqualTo(1224);
    }
}
