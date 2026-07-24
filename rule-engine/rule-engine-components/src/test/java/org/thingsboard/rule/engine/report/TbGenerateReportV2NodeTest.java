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
package org.thingsboard.rule.engine.report;

import com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.JobManager;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.report.ReportConfig;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class TbGenerateReportV2NodeTest {

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final DeviceId originator = new DeviceId(UUID.randomUUID());
    private final RuleChainId ruleChainId = new RuleChainId(UUID.randomUUID());
    private final RuleNodeId ruleNodeId = new RuleNodeId(UUID.randomUUID());
    private final ReportTemplateId reportTemplateId = new ReportTemplateId(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());
    private final NotificationTemplateId notificationTemplateId = new NotificationTemplateId(UUID.randomUUID());

    private TbGenerateReportV2Node node;
    private TbGenerateReportV2NodeConfiguration config;

    @Mock
    private TbContext ctx;
    @Mock
    private JobManager jobManager;

    private RuleNode self;

    @BeforeEach
    void setUp() {
        node = new TbGenerateReportV2Node();
        config = new TbGenerateReportV2NodeConfiguration().defaultConfiguration();
        ReflectionTestUtils.setField(node, "config", config);
        self = new RuleNode(ruleNodeId);
        self.setRuleChainId(ruleChainId);
    }

    private void givenJobSubmissionContext() {
        given(ctx.getTenantId()).willReturn(tenantId);
        given(ctx.getSelf()).willReturn(self);
        given(ctx.getSelfId()).willReturn(ruleNodeId);
        given(ctx.getJobManager()).willReturn(jobManager);
    }

    private TbMsg newMsg(String data) {
        return TbMsg.newMsg()
                .type(TbMsgType.POST_TELEMETRY_REQUEST)
                .originator(originator)
                .copyMetaData(TbMsgMetaData.EMPTY)
                .queueName("Main")
                .data(data)
                .build();
    }

    private ReportConfig reportConfig() {
        ReportConfig reportConfig = new ReportConfig();
        reportConfig.setReportTemplateId(reportTemplateId);
        reportConfig.setUserId(userId);
        reportConfig.setTimezone("Europe/Kiev");
        reportConfig.setTargets(List.of(UUID.randomUUID()));
        reportConfig.setNotificationTemplateId(notificationTemplateId);
        return reportConfig;
    }

    @Test
    void givenStaticConfig_whenOnMsg_thenSubmitsReportJobWithProtoAndRuleNode() {
        config.setUseConfigFromMessage(false);
        config.setConfig(reportConfig());
        givenJobSubmissionContext();
        given(jobManager.submitJob(any(Job.class))).willAnswer(inv -> Futures.immediateFuture(inv.getArgument(0)));

        TbMsg msg = newMsg(TbMsg.EMPTY_JSON_OBJECT);
        node.onMsg(ctx, msg);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        then(jobManager).should().submitJob(jobCaptor.capture());
        Job job = jobCaptor.getValue();

        // F1 boundary: tenantId is the rule chain's own tenant (ctx), never from config (ReportConfig
        // has no tenantId field to inject one anyway) or the message.
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        assertThat(job.getType()).isEqualTo(JobType.REPORT);
        assertThat(job.getEntityId()).isEqualTo(reportTemplateId);

        ReportJobConfiguration jobConfig = job.getConfiguration();
        assertThat(jobConfig.getReportTemplateId()).isEqualTo(reportTemplateId);
        assertThat(jobConfig.getUserId()).isEqualTo(userId);
        assertThat(jobConfig.getTimezone()).isEqualTo("Europe/Kiev");
        assertThat(jobConfig.getNotificationTemplateId()).isEqualTo(notificationTemplateId);
        assertThat(jobConfig.getOriginator()).isEqualTo(originator);
        assertThat(jobConfig.getQueueName()).isEqualTo("Main");
        // Set only by the rule node -> ReportJobProcessor.produceOutputMsg pushes the result back.
        assertThat(jobConfig.getRuleNode()).isEqualTo(self);
        assertThat(jobConfig.getOutputTbMsgProto()).isNotBlank();

        then(ctx).should(never()).tellFailure(any(), any());
    }

    @Test
    void givenUseConfigFromMessage_whenOnMsg_thenParsesConfigFromMessageDataButKeepsCtxTenant() {
        config.setUseConfigFromMessage(true);
        config.setConfig(null); // must be ignored; config comes from the message
        givenJobSubmissionContext();
        given(jobManager.submitJob(any(Job.class))).willAnswer(inv -> Futures.immediateFuture(inv.getArgument(0)));

        TbMsg msg = newMsg(JacksonUtil.toString(reportConfig()));
        node.onMsg(ctx, msg);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        then(jobManager).should().submitJob(jobCaptor.capture());
        Job job = jobCaptor.getValue();

        // Even with device-influenced message data driving the ReportConfig, the tenant stays the
        // ctx tenant (the F1 anchor), and the message-supplied ids are honoured within it.
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        ReportJobConfiguration jobConfig = job.getConfiguration();
        assertThat(jobConfig.getReportTemplateId()).isEqualTo(reportTemplateId);
        assertThat(jobConfig.getUserId()).isEqualTo(userId);
        assertThat(jobConfig.getOutputTbMsgProto()).isNotBlank();
    }

    @Test
    void givenSubmitJobFails_whenOnMsg_thenTellFailure() {
        config.setUseConfigFromMessage(false);
        config.setConfig(reportConfig());
        givenJobSubmissionContext();
        RuntimeException error = new RuntimeException("job submission failed");
        given(jobManager.submitJob(any(Job.class))).willReturn(Futures.immediateFailedFuture(error));

        TbMsg msg = newMsg(TbMsg.EMPTY_JSON_OBJECT);
        node.onMsg(ctx, msg);

        // The original inbound msg is failed, with the submission error.
        then(ctx).should().tellFailure(eq(msg), eq(error));
    }

    @Test
    void givenMissingConfig_whenOnMsg_thenThrows() {
        config.setUseConfigFromMessage(false);
        config.setConfig(null);

        TbMsg msg = newMsg(TbMsg.EMPTY_JSON_OBJECT);
        assertThatThrownBy(() -> node.onMsg(ctx, msg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report configuration is missing");
    }

}
