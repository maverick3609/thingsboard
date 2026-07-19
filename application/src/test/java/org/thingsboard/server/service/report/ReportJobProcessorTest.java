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
package org.thingsboard.server.service.report;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.rule.engine.api.NotificationCenter;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.JobId;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobStatus;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.job.ReportJobResult;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.job.task.ReportTaskResult;
import org.thingsboard.server.common.data.job.task.Task;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.info.ReportGeneratedNotificationInfo;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.report.task.ReportTaskProcessor;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers Task 15: {@link ReportJobProcessor} turns a REPORT {@link Job} into exactly one {@link
 * ReportTask} carrying a freshly minted access token, then — once the job completes — dispatches a
 * best-effort "report generated" notification whose {@code additionalConfig.reports} carries the
 * finished report's id (surfaced via {@code ((ReportJobResult) job.getResult()).getReport()},
 * which {@code ReportJobResult#processTaskResult} already populates as tasks complete — task 5).
 * {@link ReportTaskProcessor} (bottom of this file) delegates rendering to {@link TbReportService}
 * and wraps the result in {@link ReportTaskResult#success}.
 */
@ExtendWith(MockitoExtension.class)
class ReportJobProcessorTest {

    @Mock
    private ReportTemplateService reportTemplateService;
    @Mock
    private UserService userService;
    @Mock
    private SystemSecurityService systemSecurityService;
    @Mock
    private JwtTokenFactory jwtTokenFactory;
    @Mock
    private NotificationCenter notificationCenter;
    @Mock
    private Jws<Claims> jws;
    @Mock
    private Claims claims;

    @InjectMocks
    private ReportJobProcessor processor;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());
    private final ReportTemplateId templateId = new ReportTemplateId(UUID.randomUUID());

    @Test
    void processEmitsOneTaskWithToken() throws Exception {
        ReportTemplate template = new ReportTemplate(templateId);
        template.setTenantId(tenantId);
        template.setFormat(TbReportFormat.PDF);
        when(reportTemplateService.findReportTemplateById(tenantId, templateId)).thenReturn(template);

        User user = new User(userId);
        user.setTenantId(tenantId);
        user.setCustomerId(customerId);
        when(userService.findUserById(tenantId, userId)).thenReturn(user);

        when(systemSecurityService.createUserAccessToken(tenantId, userId)).thenReturn("jwt");
        when(jwtTokenFactory.parseTokenClaims("jwt")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getExpiration()).thenReturn(new Date(1_700_000_000_000L));

        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .timezone("Europe/Kiev")
                .originator(tenantId)
                .build();
        Job job = buildJob(configuration);

        List<Task<?>> emitted = new ArrayList<>();
        int count = processor.process(job, emitted::add);

        assertThat(count).isEqualTo(1);
        assertThat(emitted).hasSize(1);
        ReportTask task = (ReportTask) emitted.get(0);
        assertThat(task.getAccessToken()).isEqualTo("jwt");
        assertThat(task.getAccessTokenExpirationTs()).isEqualTo(1_700_000_000_000L);
        assertThat(task.getTenantId()).isEqualTo(tenantId);
        assertThat(task.getJobId()).isEqualTo(job.getId());
        assertThat(task.getKey()).isEqualTo(configuration.getTasksKey());
        assertThat(task.getCustomerId()).isEqualTo(customerId);
        assertThat(task.getReportTemplateId()).isEqualTo(templateId);
        assertThat(task.getUserId()).isEqualTo(userId);
        assertThat(task.getUserOwnerId()).isEqualTo(customerId);
        assertThat(task.getTimezone()).isEqualTo("Europe/Kiev");
        assertThat(task.getOriginator()).isEqualTo(tenantId);
    }

    @Test
    void userOwnerIdFallsBackToTenantWhenUserHasNoCustomer() throws Exception {
        ReportTemplate template = new ReportTemplate(templateId);
        template.setTenantId(tenantId);
        template.setFormat(TbReportFormat.PDF);
        when(reportTemplateService.findReportTemplateById(tenantId, templateId)).thenReturn(template);

        User user = new User(userId);
        user.setTenantId(tenantId); // no customer -> customerId stays null
        when(userService.findUserById(tenantId, userId)).thenReturn(user);

        when(systemSecurityService.createUserAccessToken(tenantId, userId)).thenReturn("jwt");
        when(jwtTokenFactory.parseTokenClaims("jwt")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getExpiration()).thenReturn(new Date());

        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .timezone("UTC")
                .build();
        Job job = buildJob(configuration);

        List<Task<?>> emitted = new ArrayList<>();
        processor.process(job, emitted::add);

        ReportTask task = (ReportTask) emitted.get(0);
        assertThat(task.getUserOwnerId()).isEqualTo(tenantId);
        assertThat(task.getCustomerId()).isNull();
    }

    @Test
    void onJobFinishedDispatchesNotificationWithReport() {
        ReportId reportId = new ReportId(UUID.randomUUID());
        Report report = new Report(reportId);
        report.setTenantId(tenantId);
        report.setCustomerId(customerId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");

        NotificationTemplateId notificationTemplateId = new NotificationTemplateId(UUID.randomUUID());
        UUID target = UUID.randomUUID();
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .targets(List.of(target))
                .notificationTemplateId(notificationTemplateId)
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.COMPLETED);
        ((ReportJobResult) job.getResult()).setReport(report);

        processor.onJobFinished(job);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationCenter).processNotificationRequest(eq(tenantId), captor.capture(), any());
        NotificationRequest request = captor.getValue();
        // NotificationRequest's field is `additionalConfig` (getAdditionalConfig()) on the real
        // common/data class -- there is no `config`/`getConfig()` member to assert against.
        assertThat(request.getAdditionalConfig().getReports()).containsExactly(reportId);
        assertThat(request.getTargets()).containsExactly(target);
        assertThat(request.getTemplateId()).isEqualTo(notificationTemplateId);
        assertThat(request.getOriginatorEntityId()).isEqualTo(userId);
        ReportGeneratedNotificationInfo info = (ReportGeneratedNotificationInfo) request.getInfo();
        assertThat(info.getTenantId()).isEqualTo(tenantId);
        assertThat(info.getCustomerId()).isEqualTo(customerId);
        assertThat(info.getReportFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(info.getReportName()).isEqualTo("weekly.pdf");
        assertThat(info.getUserId()).isEqualTo(userId);
    }

    @Test
    void onJobFinishedSkipsNotificationWhenNoTargets() {
        Report report = new Report(new ReportId(UUID.randomUUID()));
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");

        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.COMPLETED);
        ((ReportJobResult) job.getResult()).setReport(report);

        processor.onJobFinished(job);

        verify(notificationCenter, never()).processNotificationRequest(any(), any(), any());
    }

    @Test
    void onJobFinishedNoOpWhenJobNotCompleted() {
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .targets(List.of(UUID.randomUUID()))
                .notificationTemplateId(new NotificationTemplateId(UUID.randomUUID()))
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.FAILED);

        processor.onJobFinished(job);

        verify(notificationCenter, never()).processNotificationRequest(any(), any(), any());
    }

    private Job buildJob(ReportJobConfiguration configuration) {
        Job job = Job.builder()
                .tenantId(tenantId)
                .type(JobType.REPORT)
                .key("report-job-key")
                .entityId(tenantId)
                .configuration(configuration)
                .build();
        job.setId(new JobId(UUID.randomUUID()));
        return job;
    }

    // Task 15: ReportTaskProcessor renders + persists via TbReportService, wrapping the result in
    // ReportTaskResult.success. Constructed directly (same as DummyTaskProcessor's shape) --
    // #process/#getProcessingTimeout/#getJobType never touch TaskProcessor's own @Autowired queue
    // plumbing, so a Spring context isn't needed here either.

    @Test
    void reportTaskProcessorDelegatesToTbReportServiceAndReturnsSuccess() throws Exception {
        TbReportService tbReportService = mock(TbReportService.class);
        ReportTaskProcessor taskProcessor = new ReportTaskProcessor(tbReportService);

        ReportTask task = ReportTask.builder()
                .tenantId(tenantId).jobId(new JobId(UUID.randomUUID())).key("k")
                .reportTemplateId(templateId).userId(userId).accessToken("jwt")
                .build();
        Report report = new Report(new ReportId(UUID.randomUUID()));
        when(tbReportService.generateReport(task)).thenReturn(report);

        ReportTaskResult result = taskProcessor.process(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getReport()).isSameAs(report);
        verify(tbReportService).generateReport(task);
    }

    @Test
    void reportTaskProcessorDefaultsProcessingTimeoutTo120Seconds() {
        ReportTaskProcessor taskProcessor = new ReportTaskProcessor(mock(TbReportService.class));
        ReportTask task = ReportTask.builder().tenantId(tenantId).jobId(new JobId(UUID.randomUUID())).key("k").build();

        assertThat(taskProcessor.getProcessingTimeout(task)).isEqualTo(120_000L);
    }

    @Test
    void reportTaskProcessorJobTypeIsReport() {
        assertThat(new ReportTaskProcessor(mock(TbReportService.class)).getJobType()).isEqualTo(JobType.REPORT);
    }

}
