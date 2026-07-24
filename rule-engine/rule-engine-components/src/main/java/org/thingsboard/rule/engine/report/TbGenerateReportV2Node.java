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

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.external.TbAbstractExternalNode;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.report.ReportConfig;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.Base64;
import java.util.UUID;

import static org.thingsboard.common.util.DonAsynchron.withCallback;

/**
 * "generate report" (V2) action node — PE port ({@code org.thingsboard.rule.engine.report}). Submits
 * a {@link JobType#REPORT} {@link Job} to the {@link org.thingsboard.rule.engine.api.JobManager}; the
 * report is rendered asynchronously and, once finished, pushed back into this rule chain by {@code
 * org.thingsboard.server.service.report.ReportJobProcessor#produceOutputMsg} (task J2) with the
 * generated report id in the {@code reports} output metadata (Success relation) or the failure
 * message (Failure relation).
 * <p>
 * <b>Tenant boundary (F1 anchor).</b> {@code tenantId} is ALWAYS {@link TbContext#getTenantId()} — the
 * rule chain's own tenant — never taken from {@link ReportConfig} or the message. Everything the job
 * later reads is bounded to that tenant: {@code ReportJobProcessor} resolves the report template and
 * the {@code userId} via {@code findReportTemplateById(tenantId, …)} / {@code findUserById(tenantId,
 * …)} and mints the access token via {@code createUserAccessToken(tenantId, userId)}, so a config- or
 * message-supplied id that names another tenant's entity resolves to {@code null} and fails the job
 * rather than crossing tenants.
 * <p>
 * <b>{@code useConfigFromMessage}.</b> When true the {@link ReportConfig} (including {@code userId}) is
 * parsed from {@code tbMsg.getData()}, which may be device-influenced. This is PE-parity and stays
 * inside the tenant (the {@code findUserById(tenantId, userId)} bound above); the residual risk — a
 * message picking a higher-privileged in-tenant {@code userId} so the report reads tenant-wide data —
 * is owned by the rule-chain author who opted into trusting message data. See the porting report for
 * the tradeoff analysis; no additional guard is added here (PE-faithful).
 */
@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "generate report",
        configClazz = TbGenerateReportV2NodeConfiguration.class,
        nodeDescription = "Generates report",
        nodeDetails = "Generates report, creating a \"Report generation\" task in the task manager. " +
                "The output metadata of the node contains \"reports\" field with the generated report id.",
        configDirective = "tbActionNodeGenerateReportConfig",
        icon = "description"
)
public class TbGenerateReportV2Node extends TbAbstractExternalNode {

    private TbGenerateReportV2NodeConfiguration config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        super.init(ctx);
        this.config = TbNodeUtils.convert(configuration, TbGenerateReportV2NodeConfiguration.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg tbMsg) {
        TenantId tenantId = ctx.getTenantId();
        ReportConfig reportConfig = config.isUseConfigFromMessage()
                ? JacksonUtil.fromString(tbMsg.getData(), ReportConfig.class)
                : config.getConfig();
        if (reportConfig == null) {
            throw new IllegalArgumentException("Report configuration is missing");
        }

        TbMsg msg = ackIfNeeded(ctx, tbMsg);
        // The msg the rule chain sees once the async report finishes; ReportJobProcessor decodes this
        // proto and re-emits it with the Success/Failure relation (task J2).
        TbMsg outputMsg = TbMsg.newMsg(msg, msg.getQueueName(), ctx.getSelf().getRuleChainId(), ctx.getSelfId());

        // Built the R1 way (new Job(...) + ReportJobConfiguration.builder()); CE has no PE
        // Job.newReportJob() builder. entityId = the report template; key = a fresh UUID so every
        // firing is an independent job (a stable key would let DefaultJobService's
        // "already queued or running" dedup reject rapid per-message firings).
        ReportJobConfiguration jobConfiguration = ReportJobConfiguration.builder()
                .reportTemplateId(reportConfig.getReportTemplateId())
                .userId(reportConfig.getUserId())
                .timezone(reportConfig.getTimezone())
                .targets(reportConfig.getTargets())
                .notificationTemplateId(reportConfig.getNotificationTemplateId())
                .originator(tbMsg.getOriginator())
                .ruleNode(ctx.getSelf())
                .outputTbMsgProto(Base64.getEncoder().encodeToString(TbMsg.toProto(outputMsg).toByteArray()))
                .queueName(msg.getQueueName())
                .build();
        Job job = new Job(tenantId, JobType.REPORT, UUID.randomUUID().toString(), reportConfig.getReportTemplateId(), jobConfiguration);

        // The report renders asynchronously; the rule chain is continued OUT OF BAND by
        // ReportJobProcessor.produceOutputMsg, which injects a FRESH msg down this node's Success/
        // Failure relation once the job finishes. So on a successful submit the trigger msg must be
        // TERMINATED without routing it onward — ctx.ack, NOT tellSuccess: a synchronous tellSuccess
        // would fire the Success relation now AND again when produceOutputMsg injects, double-running
        // the downstream chain. When forceAck is on, ackIfNeeded already acked; otherwise ack here so
        // the msg isn't left pending until the pack times out (which on retry queues re-fires the node
        // -> duplicate reports/emails, and on SequentialByOriginator head-of-line-blocks the originator).
        // On submit failure, route via the external-node protected tellFailure(ctx, msg, error) so the
        // Failure is delivered correctly under both forceAck modes (enqueue when acked, tell otherwise).
        withCallback(ctx.getJobManager().submitJob(job),
                result -> {
                    if (!forceAck) {
                        ctx.ack(msg);
                    }
                },
                error -> tellFailure(ctx, msg, error));
    }

}
