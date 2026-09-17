// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

// JacksonUtil lives in common/util, which is not a common/data dependency;
// use a plain ObjectMapper here per the task brief's documented fallback.
class ReportConfigTest {
    @Test void reportConfigParsesSchedulerJson() throws Exception {
        String json = "{\"reportTemplateId\":{\"entityType\":\"REPORT_TEMPLATE\",\"id\":\"784f394c-42b6-435a-983c-b7beff2784f9\"},"
            + "\"userId\":{\"entityType\":\"USER\",\"id\":\"784f394c-42b6-435a-983c-b7beff2784f9\"},"
            + "\"timezone\":\"Europe/Kiev\",\"targets\":[\"784f394c-42b6-435a-983c-b7beff2784f9\"],"
            + "\"notificationTemplateId\":{\"entityType\":\"NOTIFICATION_TEMPLATE\",\"id\":\"784f394c-42b6-435a-983c-b7beff2784f9\"}}";
        ObjectMapper mapper = new ObjectMapper();
        ReportConfig cfg = mapper.readValue(json, ReportConfig.class);
        assertThat(cfg.getTimezone()).isEqualTo("Europe/Kiev");
        assertThat(cfg.getTargets()).hasSize(1);
        assertThat(cfg.getReportTemplateId()).isNotNull();
    }
}
