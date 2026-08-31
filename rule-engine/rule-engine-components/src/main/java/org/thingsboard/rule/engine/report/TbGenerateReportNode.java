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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.DashboardReportService;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.external.TbAbstractExternalNode;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import static org.thingsboard.common.util.DonAsynchron.withCallback;

/**
 * "generate dashboard report" action node — PE port ({@code
 * org.thingsboard.rule.engine.report.TbGenerateReportNode}). Renders the configured dashboard,
 * stores the result, and stamps the stored report's id into the outgoing message's metadata so a
 * downstream {@code to email} node can attach it.
 * <p>
 * <b>This is what makes {@code generateDashboardReport} scheduler events do something.</b>
 * {@code DefaultSchedulerService} turns such an event into a rule-engine message of type {@code
 * generateReport} whose data is the event's {@code msgBody} — i.e. {@code {"reportConfig": {...}}} —
 * which is exactly the shape {@code useReportConfigFromMessage} reads. The tenant still has to route
 * it: root rule chain -> Message Type Switch -> the <b>Generate Report</b> relation -> this node.
 * Without that wiring the message falls off the switch and the event does nothing, same as before.
 * <p>
 * <b>Deviations from PE</b> (both forced by what this fork has, and both documented on the classes
 * involved):
 * <ul>
 *   <li>No {@code useSystemReportsServer}/{@code reportsServerEndpointUrl} — see
 *       {@link TbGenerateReportNodeConfiguration}. Rendering is in-process.</li>
 *   <li>PE saves a {@code BlobEntity} and stamps its id into an {@code attachments} metadata key.
 *       This fork has no blob store; the equivalent artifact is a {@link Report}, so the id goes
 *       into the {@code reports} metadata key instead — the same key {@code
 *       ReportJobProcessor#produceOutputMsg} already uses for templated reports, and the one {@code
 *       TbMsgToEmailNode} reads into {@code TbEmail.reports}.</li>
 * </ul>
 * <p>
 * <b>Tenant boundary.</b> {@code tenantId} is always {@link TbContext#getTenantId()} — the rule
 * chain's own tenant — never taken from the message. The config-supplied {@code userId} is resolved
 * with {@code findUserById(tenantId, ...)} behind the service seam, so an id naming another tenant's
 * user resolves to null and fails the message rather than crossing tenants.
 */
@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "generate dashboard report",
        configClazz = TbGenerateReportNodeConfiguration.class,
        nodeDescription = "Generates dashboard report",
        nodeDetails = "Generates dashboard based reports. The generated report id is added to the " +
                "outgoing message's \"reports\" metadata field, which the \"to email\" node reads to " +
                "attach the report to an outgoing email.",
        configDirective = "tbActionNodeGenerateDashboardReportConfig",
        icon = "description"
)
public class TbGenerateReportNode extends TbAbstractExternalNode {

    private static final String REPORTS = "reports";

    private TbGenerateReportNodeConfiguration config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        super.init(ctx);
        this.config = TbNodeUtils.convert(configuration, TbGenerateReportNodeConfiguration.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        TbMsg ackedMsg = ackIfNeeded(ctx, msg);
        DashboardReportConfig reportConfig;
        DashboardReportService reportService;
        try {
            reportConfig = resolveConfig(msg);
            reportService = ctx.getDashboardReportService();
            if (reportService == null) {
                throw new IllegalStateException("Dashboard report renderer is not enabled. Set " +
                        "reports.renderer.enabled=true (env REPORTS_RENDERER_ENABLED=true) on a " +
                        "headless-Chromium-capable host.");
            }
        } catch (Exception e) {
            tellFailure(ctx, ackedMsg, e);
            return;
        }
        // Off the rule-engine thread. This fork's renderer is synchronous (PE's takes callbacks) and a
        // dashboard render drives a headless browser for seconds to minutes; running it inline would
        // hold a rule-engine actor thread long enough to trip the pack processing timeout, which on a
        // retry queue re-fires the node and generates the report twice.
        DashboardReportConfig cfg = reportConfig;
        DashboardReportService svc = reportService;
        TenantId tenantId = ctx.getTenantId();
        withCallback(ctx.getExternalCallExecutor().executeAsync(() -> svc.generateAndSaveReport(tenantId, cfg)),
                report -> tellSuccess(ctx, withReportId(ackedMsg, report)),
                error -> tellFailure(ctx, ackedMsg, error));
    }

    private DashboardReportConfig resolveConfig(TbMsg msg) {
        if (!config.isUseReportConfigFromMessage()) {
            DashboardReportConfig configured = config.getReportConfig();
            if (configured == null) {
                throw new IllegalArgumentException("Report configuration is missing");
            }
            return configured;
        }
        DashboardReportConfig fromMsg;
        try {
            JsonNode data = JacksonUtil.toJsonNode(msg.getData());
            fromMsg = JacksonUtil.treeToValue(data.get("reportConfig"), DashboardReportConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Incoming message doesn't contain valid reportConfig JSON configuration!", e);
        }
        if (fromMsg == null) {
            throw new RuntimeException("Incoming message doesn't contain valid reportConfig JSON configuration!");
        }
        return fromMsg;
    }

    /**
     * Appends to {@code reports} rather than overwriting it (PE does the same with {@code
     * attachments}) so a chain that generates several reports before emailing attaches all of them.
     */
    private static TbMsg withReportId(TbMsg msg, Report report) {
        TbMsgMetaData metaData = msg.getMetaData().copy();
        String existing = metaData.getValue(REPORTS);
        String reportId = report.getId().getId().toString();
        metaData.putValue(REPORTS, StringUtils.isEmpty(existing) ? reportId : existing + "," + reportId);
        return msg.transform().metaData(metaData).build();
    }

}
