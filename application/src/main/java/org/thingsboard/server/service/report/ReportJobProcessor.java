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

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.DebugModeUtil;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobStatus;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.job.ReportJobResult;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.job.task.Task;
import org.thingsboard.server.common.data.job.task.TaskResult;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.gen.MsgProtos;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.common.SimpleTbQueueCallback;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.service.job.JobProcessor;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * PE {@code ReportJobProcessor} (design spec, reporting task 15), ported down to R1 scope: turns a
 * {@code REPORT} {@link Job} into exactly one {@link ReportTask}, then — once the job framework
 * ({@link org.thingsboard.server.service.job.DefaultJobManager}) reports the job {@link
 * JobStatus#COMPLETED} — dispatches a best-effort "report generated" notification. Auto-discovered
 * by {@code DefaultJobManager} via {@link #getType()} (verified: task 5), no registration needed.
 * <p>
 * Two PE collaborators are intentionally not ported in R1 (marked {@code // R2} at their call
 * sites below): the {@code TbApiUsageStateService} report-creation-enabled gate, and {@code
 * OwnersCacheService#getOwner} (replaced with a direct customer-id-else-tenant-id resolution).
 * <p>
 * Deliberately does NOT depend on {@link TbReportService} — only {@code
 * org.thingsboard.server.service.report.task.ReportTaskProcessor} renders/persists the report —
 * so this bean can wire unconditionally, unlike {@code TbReportService} which is gated behind
 * {@code reports.renderer.enabled}.
 * <p>
 * <b>R2b task J2 — rule-engine push-back.</b> {@link #produceOutputMsg} re-emits the finished report
 * into the originating rule chain: a REPORT job carrying an {@code outputTbMsgProto} (set only by the
 * {@code generate report} V2 rule node) is decoded back into a {@link TbMsg} and pushed to the rule
 * engine with the {@code Success} relation (plus the generated report id in the {@code reports}
 * metadata) or the {@code Failure} relation. Scheduler-originated jobs carry no proto, so this is a
 * no-op for them.
 * <p>
 * <b>R2b — renderer opt-in / no orphan jobs.</b> The rule node is a job-producing entrypoint that,
 * unlike R1's scheduler/controller entrypoints, cannot check {@code reports.renderer.enabled} itself
 * (a rule node is not a Spring bean and {@code TbContext} exposes neither the property nor a
 * renderer-gated bean). So the gate lives here, at the always-on intake that would otherwise emit an
 * unconsumable {@link ReportTask}: when the renderer is disabled {@link #process} throws, the job is
 * marked FAILED (never orphaned — the {@link org.thingsboard.server.service.report.task.ReportTaskProcessor}
 * consumer is absent when disabled), and the Failure is pushed back to the rule chain via
 * {@link #produceOutputMsg}.
 */
@Component
@Slf4j
public class ReportJobProcessor implements JobProcessor {

    private final ReportTemplateService reportTemplateService;
    private final UserService userService;
    private final SystemSecurityService systemSecurityService;
    private final JwtTokenFactory jwtTokenFactory;
    private final ReportNotificationDispatcher reportNotificationDispatcher;
    private final TbClusterService clusterService;
    private final PartitionService partitionService;
    private final ActorSystemContext actorSystemContext;

    // Renderer opt-in gate (see class doc): the rule-node entrypoint can't check this flag itself, so
    // this always-on intake refuses REPORT jobs when the renderer is off rather than orphan them.
    @Value("${reports.renderer.enabled:false}")
    private boolean rendererEnabled;

    public ReportJobProcessor(ReportTemplateService reportTemplateService,
                               UserService userService,
                               SystemSecurityService systemSecurityService,
                               JwtTokenFactory jwtTokenFactory,
                               ReportNotificationDispatcher reportNotificationDispatcher,
                               TbClusterService clusterService,
                               PartitionService partitionService,
                               @Lazy ActorSystemContext actorSystemContext) {
        this.reportTemplateService = reportTemplateService;
        this.userService = userService;
        this.systemSecurityService = systemSecurityService;
        this.jwtTokenFactory = jwtTokenFactory;
        this.reportNotificationDispatcher = reportNotificationDispatcher;
        this.clusterService = clusterService;
        this.partitionService = partitionService;
        this.actorSystemContext = actorSystemContext;
    }

    @Override
    public int process(Job job, Consumer<Task<?>> taskConsumer) throws Exception {
        TenantId tenantId = job.getTenantId();
        ReportJobConfiguration configuration = job.getConfiguration();
        log.info("[{}][{}] Processing report job for template [{}]", tenantId, job.getId(), configuration.getReportTemplateId());

        // Renderer opt-in gate (R2b): mirrors the /report/request controller guard, but here rather
        // than at the entrypoint because the "generate report" rule node — a job-producing entrypoint
        // that cannot read reports.renderer.enabled — can still submit a REPORT job when disabled.
        // Throwing before any task is emitted makes DefaultJobManager#processJob mark the job FAILED
        // (no orphan task: ReportTaskProcessor, the task consumer, is absent when disabled), and
        // onJobFinished then pushes the Failure back to the rule chain.
        if (!rendererEnabled) {
            throw new IllegalStateException("Report renderer is not enabled");
        }

        // R2: PE gates on apiUsageStateService.getApiUsageState(tenantId).isReportCreationEnabled();
        // stubbed to always-enabled until API usage tracking is ported for reports.
        boolean reportCreationEnabled = true;
        if (!reportCreationEnabled) {
            throw new IllegalStateException("Report creation is disabled");
        }

        ReportTemplate reportTemplate = reportTemplateService.findReportTemplateById(tenantId, configuration.getReportTemplateId());
        if (reportTemplate == null) {
            throw new IllegalArgumentException("Report template not found: " + configuration.getReportTemplateId());
        }
        User user = userService.findUserById(tenantId, configuration.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + configuration.getUserId());
        }

        String accessToken = systemSecurityService.createUserAccessToken(tenantId, configuration.getUserId());
        long accessTokenExpirationTs = jwtTokenFactory.parseTokenClaims(accessToken).getPayload().getExpiration().getTime();

        // R2: PE resolves the owner via OwnersCacheService#getOwner(tenantId, userId); R1 stand-in
        // below (customer if the user belongs to one, tenant otherwise).
        CustomerId customerId = user.getCustomerId();
        EntityId userOwnerId = (customerId == null || customerId.isNullUid()) ? tenantId : customerId;

        ReportTask task = ReportTask.builder()
                .tenantId(tenantId)
                .jobId(job.getId())
                .key(configuration.getTasksKey())
                .customerId(user.getCustomerId())
                .reportTemplateId(reportTemplate.getId())
                .reportTemplateConfig(reportTemplate.getConfiguration())
                .timezone(configuration.getTimezone())
                .userId(configuration.getUserId())
                .userOwnerId(userOwnerId)
                .originator(configuration.getOriginator())
                .accessToken(accessToken)
                .accessTokenExpirationTs(accessTokenExpirationTs)
                .build();
        taskConsumer.accept(task);
        log.info("[{}][{}] Submitted 1 report generation task for template [{}]", tenantId, job.getId(), configuration.getReportTemplateId());
        return 1;
    }

    @Override
    public void reprocess(Job job, List<TaskResult> taskFailures, Consumer<Task<?>> taskConsumer) throws Exception {
        process(job, taskConsumer);
    }

    @Override
    public void onJobFinished(Job job) {
        TenantId tenantId = job.getTenantId();
        ReportJobConfiguration configuration = job.getConfiguration();
        ReportJobResult result = (ReportJobResult) job.getResult();

        // R2b (task J2): a rule-node-originated REPORT job carries an outputTbMsgProto -> push the
        // finished report back into the originating rule chain (Success + "reports" metadata on
        // completion, or Failure). Scheduler-originated jobs have no proto, so this is a no-op.
        // Best-effort: a push failure is logged and must not stop the notification dispatch below.
        if (configuration.getOutputTbMsgProto() != null) {
            try {
                produceOutputMsg(job);
            } catch (Exception e) {
                log.error("[{}][{}] Failed to produce rule engine output msg", tenantId, job.getId(), e);
            }
        }

        if (job.getStatus() != JobStatus.COMPLETED) {
            return;
        }
        Report report = result.getReport();
        if (report == null) {
            log.warn("[{}][{}] Report job completed without a report result, skipping notification", tenantId, job.getId());
            return;
        }
        log.info("[{}][{}] Report job finished, report [{}]", tenantId, job.getId(), report.getId());

        reportNotificationDispatcher.dispatch(tenantId, report, configuration.getTargets(),
                configuration.getNotificationTemplateId(), job.getId().toString());
    }

    /**
     * PE {@code ReportJobProcessor#produceOutputMsg}, ported to CE (task J2). Decodes the rule node's
     * {@code outputTbMsgProto} back into a {@link TbMsg}, tags it {@code Success} (with the generated
     * report id in the {@code reports} metadata) or {@code Failure}, and pushes it to the rule engine
     * so the {@code generate report} node's chain continues. Only invoked when {@code
     * outputTbMsgProto} is present (rule-node jobs); scheduler jobs never reach here.
     * <p>
     * Deviation from PE, which derives success/failure from a private {@code getError(ReportJobResult)}
     * helper: R1 report jobs are single-task (COMPLETED ⇔ the one task succeeded and a report is
     * present), so success/failure is read straight off {@link Job#getStatus()} and the failure text
     * off the existing {@link Job#getError()} — no duplicate error-extraction helper.
     */
    private void produceOutputMsg(Job job) {
        TenantId tenantId = job.getTenantId();
        ReportJobConfiguration configuration = job.getConfiguration();
        ReportJobResult result = (ReportJobResult) job.getResult();

        TbMsg outputMsg;
        try {
            outputMsg = TbMsg.fromProto(configuration.getQueueName(),
                    MsgProtos.TbMsgProto.parseFrom(Base64.getDecoder().decode(configuration.getOutputTbMsgProto())), null);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        String relationType;
        String failureMessage;
        if (job.getStatus() == JobStatus.COMPLETED) {
            relationType = TbNodeConnectionType.SUCCESS;
            failureMessage = null;
            Report report = result.getReport();
            if (report != null) {
                outputMsg.getMetaData().putValue("reports", report.getId().toString());
            }
        } else {
            relationType = TbNodeConnectionType.FAILURE;
            failureMessage = job.getError();
        }

        TransportProtos.ToRuleEngineMsg.Builder ruleEngineMsg = TransportProtos.ToRuleEngineMsg.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setTbMsgProto(TbMsg.toProto(outputMsg))
                .addRelationTypes(relationType);
        if (failureMessage != null) {
            ruleEngineMsg.setFailureMessage(failureMessage);
        }
        TopicPartitionInfo tpi = partitionService.resolve(ServiceType.TB_RULE_ENGINE, outputMsg.getQueueName(), tenantId, outputMsg.getOriginator());
        clusterService.pushMsgToRuleEngine(tpi, outputMsg.getId(), ruleEngineMsg.build(), new SimpleTbQueueCallback(
                metadata -> persistDebugOutput(tenantId, configuration.getRuleNode(), outputMsg, relationType, failureMessage),
                throwable -> log.error("[{}] Failed to push report output msg to rule engine", tenantId, throwable)));
    }

    /**
     * CE equivalent of PE's {@code actorSystemContext.persistDebugOutputIfNeeded}: persist a rule-node
     * debug OUT event for the pushed msg, gated on the node's own debug settings (mirrors {@code
     * DefaultTbContext#persistDebugOutput}). No-op if the job carries no rule node.
     */
    private void persistDebugOutput(TenantId tenantId, RuleNode ruleNode, TbMsg outputMsg, String relationType, String failureMessage) {
        if (ruleNode == null) {
            return;
        }
        Set<String> relationTypes = Set.of(relationType);
        if (DebugModeUtil.isDebugAllAvailable(ruleNode)) {
            actorSystemContext.persistDebugOutput(tenantId, ruleNode.getId(), outputMsg, relationType, null, failureMessage);
        } else if (DebugModeUtil.isDebugFailuresAvailable(ruleNode, relationTypes)) {
            actorSystemContext.persistDebugOutput(tenantId, ruleNode.getId(), outputMsg, TbNodeConnectionType.FAILURE, null, failureMessage);
        }
    }

    @Override
    public JobType getType() {
        return JobType.REPORT;
    }

}
