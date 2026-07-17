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

import static org.assertj.core.api.Assertions.assertThat;

// JacksonUtil lives in common/util, which is not a common/data dependency;
// use a plain ObjectMapper here per the task brief's documented fallback.
class ReportTemplateTest {

    @Test
    void configurationPassthroughKeepsUnknownKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cfg = mapper.readTree("{\"type\":\"PDF\",\"components\":[{\"type\":\"DASHBOARD\",\"dashboardId\":\"d1\",\"pageWidth\":800,\"futureKey\":true}]}");

        ReportTemplate t = new ReportTemplate();
        t.setName("Weekly");
        t.setFormat(TbReportFormat.PDF);
        t.setType(ReportTemplateType.REPORT);
        t.setConfiguration(cfg);

        ReportTemplate back = mapper.readValue(mapper.writeValueAsString(t), ReportTemplate.class);

        assertThat(back.getConfiguration().get("components").get(0).get("futureKey").asBoolean()).isTrue();
        assertThat(back.getType()).isEqualTo(ReportTemplateType.REPORT);
    }
}
