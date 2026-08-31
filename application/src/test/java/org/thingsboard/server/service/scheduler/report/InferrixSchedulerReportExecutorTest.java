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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.thingsboard.rule.engine.api.MailService;
import org.thingsboard.rule.engine.api.TbEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.server.service.report.ReportNotificationDispatcher;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.rule.engine.api.DashboardReportService;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.given;
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
    @Mock
    private DashboardReportService dashboardReportService;
    @Mock
    private ReportNotificationDispatcher notificationDispatcher;
    @Mock
    private MailService mailService;

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
        assertThat(job.getEntityId()).isEqualTo(reportTemplateId);
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

    // --- generateDashboardReport: direct execution, no rule-chain hop ---

    /** Exactly the body DefaultSchedulerService hands over for a generateDashboardReport event. */
    private static SchedulerEvent dashboardEvent(String extraTopLevelFields) {
        SchedulerEvent event = new SchedulerEvent(new SchedulerEventId(UUID.randomUUID()));
        event.setName("IOCL Water Meter Report Pune Monthly");
        event.setType("generateDashboardReport");
        event.setConfiguration(JacksonUtil.toJsonNode(
                "{\"msgType\":null,\"metadata\":null" + extraTopLevelFields + ",\"msgBody\":{\"reportConfig\":{" +
                        "\"baseUrl\":\"https://old-pe.example.com\"," +
                        "\"dashboardId\":\"11111111-1111-1111-1111-111111111111\"," +
                        "\"state\":\"s\",\"timezone\":\"Asia/Calcutta\"," +
                        "\"useDashboardTimewindow\":true,\"namePattern\":\"monthly\"," +
                        "\"type\":\"pdf\",\"useCurrentUserCredentials\":false," +
                        "\"userId\":\"22222222-2222-2222-2222-222222222222\"}}}"));
        return event;
    }

    private static Report savedReport() {
        Report report = new Report(new ReportId(UUID.randomUUID()));
        report.setName("monthly.pdf");
        return report;
    }

    @Test
    void rendersDashboardReportAndDispatchesItToTheEventsTargets() {
        UUID target = UUID.randomUUID();
        NotificationTemplateId notificationTemplateId = new NotificationTemplateId(UUID.randomUUID());
        SchedulerEvent event = dashboardEvent(",\"targets\":[\"" + target + "\"]," +
                "\"notificationTemplateId\":{\"entityType\":\"NOTIFICATION_TEMPLATE\",\"id\":\"" +
                notificationTemplateId.getId() + "\"}");
        Report report = savedReport();
        given(dashboardReportService.generateAndSaveReport(eq(tenantId), any(DashboardReportConfig.class)))
                .willReturn(report);

        executor.executeDashboardReport(tenantId, event);

        ArgumentCaptor<DashboardReportConfig> configCaptor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        // The render is submitted to a pool, not run inline (the caller is the single scheduler thread).
        then(dashboardReportService).should(timeout(5000)).generateAndSaveReport(eq(tenantId), configCaptor.capture());
        assertThat(configCaptor.getValue().getDashboardId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(configCaptor.getValue().getUserId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        then(notificationDispatcher).should(timeout(5000))
                .dispatch(tenantId, report, List.of(target), notificationTemplateId, event.getId().toString());
        // The rule engine is not involved at all - that is the whole point of this path.
        then(jobManager).shouldHaveNoInteractions();
    }

    @Test
    void legacyEventWithoutTargetsStillGeneratesAndLetsTheDispatcherExplainTheSilence() {
        SchedulerEvent event = dashboardEvent("");
        Report report = savedReport();
        given(dashboardReportService.generateAndSaveReport(any(), any())).willReturn(report);

        executor.executeDashboardReport(tenantId, event);

        then(dashboardReportService).should(timeout(5000)).generateAndSaveReport(any(), any());
        then(notificationDispatcher).should(timeout(5000))
                .dispatch(eq(tenantId), eq(report), eq(List.of()), isNull(), any());
    }

    @Test
    void doesNotBlockTheCallerWhileTheDashboardRenders() throws Exception {
        CountDownLatch renderStarted = new CountDownLatch(1);
        CountDownLatch releaseRender = new CountDownLatch(1);
        given(dashboardReportService.generateAndSaveReport(any(), any())).willAnswer(inv -> {
            renderStarted.countDown();
            releaseRender.await(5, TimeUnit.SECONDS);
            return savedReport();
        });

        try {
            executor.executeDashboardReport(tenantId, dashboardEvent(""));
            // Returned already: DefaultSchedulerService fires every event on ONE thread, so blocking
            // here would stall every other tenant's scheduler events for the whole render.
            assertThat(renderStarted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseRender.countDown();
        }
    }

    @Test
    void malformedConfigurationIsRejectedBeforeAnythingIsSubmitted() {
        SchedulerEvent event = new SchedulerEvent(new SchedulerEventId(UUID.randomUUID()));
        event.setName("Broken");
        event.setType("generateDashboardReport");
        event.setConfiguration(JacksonUtil.toJsonNode("{\"msgType\":null,\"msgBody\":{},\"metadata\":null}"));

        assertThatThrownBy(() -> executor.executeDashboardReport(tenantId, event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("msgBody.reportConfig");

        then(dashboardReportService).shouldHaveNoInteractions();
    }

    /**
     * PE's own delivery contract for this event type: msgBody.sendEmail + msgBody.emailConfig. PE needs
     * a rule chain to act on it; honouring it here is what removes that requirement.
     */
    @Test
    void peSendEmailConfigurationIsHonouredWithoutAnyRuleChain() throws Exception {
        SchedulerEvent event = dashboardEvent("");
        ObjectNode msgBody = (ObjectNode) event.getConfiguration().get("msgBody");
        msgBody.put("sendEmail", true);
        ObjectNode emailConfig = msgBody.putObject("emailConfig");
        emailConfig.put("from", "reports@inferrix.com");
        emailConfig.put("to", "ops@inferrix.com");
        emailConfig.put("subject", "Report generated on %d{yyyy}");
        emailConfig.put("body", "See attached.");
        Report report = savedReport();
        given(dashboardReportService.generateAndSaveReport(any(), any())).willReturn(report);

        executor.executeDashboardReport(tenantId, event);

        ArgumentCaptor<TbEmail> captor = ArgumentCaptor.forClass(TbEmail.class);
        then(mailService).should(timeout(5000)).send(eq(tenantId), any(), captor.capture());
        TbEmail email = captor.getValue();
        assertThat(email.getTo()).isEqualTo("ops@inferrix.com");
        assertThat(email.getFrom()).isEqualTo("reports@inferrix.com");
        // %d{...} is substituted the same way PE substitutes it in report names.
        assertThat(email.getSubject()).matches("Report generated on \\d{4}");
        // The rendered report rides along as the attachment (DefaultMailService resolves ReportIds).
        assertThat(email.getReports()).containsExactly(report.getId());
    }

    @Test
    void noEmailIsSentWhenPeSendEmailIsOff() {
        given(dashboardReportService.generateAndSaveReport(any(), any())).willReturn(savedReport());

        executor.executeDashboardReport(tenantId, dashboardEvent(""));

        then(notificationDispatcher).should(timeout(5000)).dispatch(any(), any(), any(), any(), any());
        then(mailService).shouldHaveNoInteractions();
    }

    @Test
    void mailFailureDoesNotStopTheNotificationTargetPath() throws Exception {
        UUID target = UUID.randomUUID();
        SchedulerEvent event = dashboardEvent(",\"targets\":[\"" + target + "\"]");
        ObjectNode msgBody = (ObjectNode) event.getConfiguration().get("msgBody");
        msgBody.put("sendEmail", true);
        msgBody.putObject("emailConfig").put("to", "ops@inferrix.com");
        Report report = savedReport();
        given(dashboardReportService.generateAndSaveReport(any(), any())).willReturn(report);
        willThrow(new RuntimeException("smtp down")).given(mailService).send(any(), any(), any());

        executor.executeDashboardReport(tenantId, event);

        then(notificationDispatcher).should(timeout(5000))
                .dispatch(eq(tenantId), eq(report), eq(List.of(target)), isNull(), any());
    }

}
