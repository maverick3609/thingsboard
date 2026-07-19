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
package org.thingsboard.server.service.scheduler.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.JobManager;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers Task 16: {@link InferrixSchedulerReportExecutor} is the real {@link
 * SchedulerReportExecutor} bean that {@code DefaultSchedulerService} invokes (via its {@code
 * Optional<SchedulerReportExecutor>} seam) when a {@code generateReport} scheduler event fires.
 * Turns the event's configuration into a submitted {@link JobType#REPORT} {@link Job} (design spec
 * §8). {@link JobManager} is mocked -- no real job submission/persistence.
 */
@ExtendWith(MockitoExtension.class)
class InferrixSchedulerReportExecutorTest {

    @Mock
    private JobManager jobManager;

    @InjectMocks
    private InferrixSchedulerReportExecutor executor;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());

    @Test
    void submitsReportJobFromConfig() {
        ReportTemplateId reportTemplateId = new ReportTemplateId(UUID.randomUUID());
        UserId userId = new UserId(UUID.randomUUID());
        NotificationTemplateId notificationTemplateId = new NotificationTemplateId(UUID.randomUUID());
        UUID target = UUID.randomUUID();

        SchedulerEvent event = new SchedulerEvent(new SchedulerEventId(UUID.randomUUID()));
        event.setName("Weekly");
        event.setType("generateReport");
        event.setConfiguration(JacksonUtil.toJsonNode(
                "{\"reportTemplateId\":{\"entityType\":\"REPORT_TEMPLATE\",\"id\":\"" + reportTemplateId.getId() + "\"}," +
                        "\"userId\":{\"entityType\":\"USER\",\"id\":\"" + userId.getId() + "\"}," +
                        "\"timezone\":\"UTC\"," +
                        "\"targets\":[\"" + target + "\"]," +
                        "\"notificationTemplateId\":{\"entityType\":\"NOTIFICATION_TEMPLATE\",\"id\":\"" + notificationTemplateId.getId() + "\"}}"));

        executor.executeReport(tenantId, event);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobManager).submitJob(captor.capture());
        Job job = captor.getValue();
        assertThat(job.getType()).isEqualTo(JobType.REPORT);
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        assertThat(job.getEntityId()).isEqualTo(event.getId());
        assertThat(job.getKey()).isEqualTo(event.getId().toString());

        ReportJobConfiguration cfg = job.getConfiguration();
        assertThat(cfg.getReportTemplateId()).isEqualTo(reportTemplateId);
        assertThat(cfg.getUserId()).isEqualTo(userId);
        assertThat(cfg.getTimezone()).isEqualTo("UTC");
        assertThat(cfg.getTargets()).containsExactly(target);
        assertThat(cfg.getNotificationTemplateId()).isEqualTo(notificationTemplateId);
        assertThat(cfg.getSchedulerEventInfo().getId()).isEqualTo(event.getId());
        assertThat(cfg.getSchedulerEventInfo().getName()).isEqualTo("Weekly");
    }

    @Test
    void parseFailureIsRethrown() {
        SchedulerEvent event = new SchedulerEvent(new SchedulerEventId(UUID.randomUUID()));
        event.setName("Broken");
        event.setType("generateReport");
        // reportTemplateId must be a {entityType,id} object; an array can never bind to it.
        event.setConfiguration(JacksonUtil.toJsonNode("{\"reportTemplateId\":[],\"userId\":null,\"timezone\":\"UTC\"}"));

        assertThatThrownBy(() -> executor.executeReport(tenantId, event))
                .isInstanceOf(RuntimeException.class);

        verify(jobManager, never()).submitJob(any());
    }

}
