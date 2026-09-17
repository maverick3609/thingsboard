// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.job.task.Task;

import static org.assertj.core.api.Assertions.assertThat;

// JacksonUtil lives in common/util, which is not a common/data dependency;
// use a plain ObjectMapper here per the task brief's documented fallback
// (matches the SchedulerEventTest / ReportConfigTest precedent).
class ReportJobJsonTest {

    @Test
    void jobTypeHasReport() {
        assertThat(JobType.REPORT.getTasksTopic()).isEqualTo("tasks.report");
    }

    @Test
    void reportJobConfigResolvesByType() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JobConfiguration cfg = ReportJobConfiguration.builder().timezone("UTC").build();
        JobConfiguration back = mapper.readValue(mapper.writeValueAsString(cfg), JobConfiguration.class);
        assertThat(back).isInstanceOf(ReportJobConfiguration.class);
        assertThat(back.getType()).isEqualTo(JobType.REPORT);
    }

    @Test
    void reportTaskResolvesByJobType() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Task<?> t = ReportTask.builder().timezone("UTC").build();
        Task<?> back = mapper.readValue(mapper.writeValueAsString(t), Task.class);
        assertThat(back).isInstanceOf(ReportTask.class);
    }

}
