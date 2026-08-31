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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.JobId;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
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
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.info.ReportGeneratedNotificationInfo;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.TbQueueCallback;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.service.report.task.ReportTaskProcessor;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
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
    private ReportNotificationDispatcher reportNotificationDispatcher;
    @Mock
    private TbClusterService clusterService;
    @Mock
    private PartitionService partitionService;
    @Mock
    private ActorSystemContext actorSystemContext;
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

    @BeforeEach
    void enableRenderer() {
        // process() gates on this @Value flag (renderer opt-in / no orphan jobs); default it on so the
        // existing task-emission tests exercise the happy path. The gate itself is covered below.
        ReflectionTestUtils.setField(processor, "rendererEnabled", true);
    }

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

        // What gets built from the report and targets is ReportNotificationDispatcher's contract and is
        // asserted in ReportNotificationDispatcherTest; here we only pin the hand-off.
        verify(reportNotificationDispatcher).dispatch(tenantId, report, List.of(target),
                notificationTemplateId, job.getId().toString());
    }

    @Test
    void onJobFinishedPassesANullTemplateIdStraightThrough() {
        Report report = new Report(new ReportId(UUID.randomUUID()));
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");

        UUID target = UUID.randomUUID();
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .targets(List.of(target))
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.COMPLETED);
        ((ReportJobResult) job.getResult()).setReport(report);

        processor.onJobFinished(job);

        // Choosing a fallback template is the dispatcher's job, not the processor's - it must not
        // substitute one of its own here.
        verify(reportNotificationDispatcher).dispatch(tenantId, report, List.of(target), null, job.getId().toString());
    }

    @Test
    void onJobFinishedStillDelegatesWhenNoTargets() {
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

        // The "no targets -> nobody gets it" warning lives in the dispatcher, so the processor hands
        // over unconditionally rather than deciding twice in two places.
        verify(reportNotificationDispatcher).dispatch(eq(tenantId), eq(report), isNull(), isNull(), any());
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

        verify(reportNotificationDispatcher, never()).dispatch(any(), any(), any(), any(), any());
    }

    // --- R2b task J: renderer opt-in gate (no orphan jobs) + rule-engine push-back ---

    @Test
    void processThrowsAndEmitsNoTaskWhenRendererDisabled() {
        ReflectionTestUtils.setField(processor, "rendererEnabled", false);
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .build();
        Job job = buildJob(configuration);

        assertThatThrownBy(() -> processor.process(job, task -> {
            throw new AssertionError("no task must be emitted when the renderer is disabled");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Report renderer is not enabled");
        // Gate short-circuits before any template/user lookup.
        verify(reportTemplateService, never()).findReportTemplateById(any(), any());
    }

    @Test
    void onJobFinishedPushesSuccessRelationWithReportsMetadata() {
        ReportId reportId = new ReportId(UUID.randomUUID());
        Report report = new Report(reportId);
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");

        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .ruleNode(ruleNode())
                .queueName("Main")
                .outputTbMsgProto(outputTbMsgProto())
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.COMPLETED);
        ((ReportJobResult) job.getResult()).setReport(report);

        processor.onJobFinished(job);

        ArgumentCaptor<TransportProtos.ToRuleEngineMsg> captor = ArgumentCaptor.forClass(TransportProtos.ToRuleEngineMsg.class);
        verify(clusterService).pushMsgToRuleEngine(nullable(TopicPartitionInfo.class), any(UUID.class), captor.capture(), any(TbQueueCallback.class));
        TransportProtos.ToRuleEngineMsg pushed = captor.getValue();
        assertThat(pushed.getRelationTypesList()).containsExactly(TbNodeConnectionType.SUCCESS);
        assertThat(pushed.getFailureMessage()).isEmpty();
        TbMsg outputMsg = TbMsg.fromProto("Main", pushed.getTbMsgProto(), null);
        assertThat(outputMsg.getMetaData().getValue("reports")).isEqualTo(reportId.toString());
        // The rule-engine push and the notification dispatch are independent: this job has no targets,
        // so the hand-over still happens and the dispatcher is the one that decides there is nobody to
        // deliver to (see ReportNotificationDispatcherTest).
        verify(reportNotificationDispatcher).dispatch(eq(tenantId), any(), isNull(), isNull(), any());
    }

    @Test
    void onJobFinishedPushesFailureRelationOnFailedJob() {
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .ruleNode(ruleNode())
                .queueName("Main")
                .outputTbMsgProto(outputTbMsgProto())
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.FAILED);
        ((ReportJobResult) job.getResult()).setGeneralError("render boom");

        processor.onJobFinished(job);

        ArgumentCaptor<TransportProtos.ToRuleEngineMsg> captor = ArgumentCaptor.forClass(TransportProtos.ToRuleEngineMsg.class);
        verify(clusterService).pushMsgToRuleEngine(nullable(TopicPartitionInfo.class), any(UUID.class), captor.capture(), any(TbQueueCallback.class));
        TransportProtos.ToRuleEngineMsg pushed = captor.getValue();
        assertThat(pushed.getRelationTypesList()).containsExactly(TbNodeConnectionType.FAILURE);
        assertThat(pushed.getFailureMessage()).isEqualTo("render boom");
    }

    @Test
    void onJobFinishedWithoutProtoDoesNotPushToRuleEngine() {
        Report report = new Report(new ReportId(UUID.randomUUID()));
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setFormat(TbReportFormat.PDF);
        report.setName("weekly.pdf");

        // Scheduler-originated job carries no outputTbMsgProto -> push-back is a no-op.
        ReportJobConfiguration configuration = ReportJobConfiguration.builder()
                .reportTemplateId(templateId)
                .userId(userId)
                .build();
        Job job = buildJob(configuration);
        job.setStatus(JobStatus.COMPLETED);
        ((ReportJobResult) job.getResult()).setReport(report);

        processor.onJobFinished(job);

        verify(clusterService, never()).pushMsgToRuleEngine(any(TopicPartitionInfo.class), any(UUID.class), any(TransportProtos.ToRuleEngineMsg.class), any(TbQueueCallback.class));
    }

    private RuleNode ruleNode() {
        RuleNode ruleNode = new RuleNode(new RuleNodeId(UUID.randomUUID()));
        ruleNode.setRuleChainId(new RuleChainId(UUID.randomUUID()));
        return ruleNode;
    }

    private String outputTbMsgProto() {
        TbMsg outputMsg = TbMsg.newMsg()
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(new DeviceId(UUID.randomUUID()))
                .copyMetaData(TbMsgMetaData.EMPTY)
                .queueName("Main")
                .data(TbMsg.EMPTY_JSON_OBJECT)
                .build();
        return Base64.getEncoder().encodeToString(TbMsg.toProto(outputMsg).toByteArray());
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
