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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rule.engine.api.DashboardReportService;
import org.thingsboard.rule.engine.api.JobManager;
import org.thingsboard.rule.engine.api.MailService;
import org.thingsboard.rule.engine.api.TbEmail;
import org.thingsboard.server.common.data.EntityInfo;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportConfig;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.service.report.ReportNotificationDispatcher;
import org.thingsboard.server.service.report.render.ReportUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link SchedulerReportExecutor} bean (design spec §8): turns a fired {@code generateReport}
 * scheduler event into a submitted {@link JobType#REPORT} {@link Job}, picked up from there by
 * {@code org.thingsboard.server.service.report.ReportJobProcessor} (task 15).
 * <p>
 * Guarded by the same {@code reports.renderer.enabled} property as {@code TbReportService}/{@code
 * ReportTaskProcessor} (task 13/14) so the pipeline is consistently on-or-off: renderer disabled ->
 * this bean is absent -> {@code DefaultSchedulerService}'s {@code Optional<SchedulerReportExecutor>}
 * seam falls back to its WARN log instead of submitting a report job nothing can render.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class InferrixSchedulerReportExecutor implements SchedulerReportExecutor {

    private final JobManager jobManager;
    private final DashboardReportService dashboardReportService;
    private final ReportNotificationDispatcher notificationDispatcher;
    private final MailService mailService;

    // DefaultSchedulerService fires events on a single-threaded executor, and a dashboard render drives
    // a headless browser for seconds to minutes. Rendering inline would stall every other scheduler
    // event on this node, so dashboard reports get their own bounded pool. Small on purpose: the
    // renderer itself is capped by reports.renderer.max_concurrent, and a deep queue of stale reports
    // helps nobody.
    //
    // Built here rather than via ThingsBoardExecutors.newLimitedTasksExecutor because that helper uses
    // CallerRunsPolicy: on overflow it would run the render ON THE CALLING THREAD, i.e. the single
    // scheduler thread - reintroducing exactly the stall this pool exists to prevent. AbortPolicy (the
    // default) instead throws RejectedExecutionException, which DefaultSchedulerService#processEvent
    // catches and logs per event, so an overloaded node drops individual reports loudly rather than
    // freezing every tenant's scheduler.
    private final ExecutorService renderExecutor = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50), ThingsBoardThreadFactory.forName("scheduler-dashboard-report"));

    public InferrixSchedulerReportExecutor(JobManager jobManager,
                                           DashboardReportService dashboardReportService,
                                           ReportNotificationDispatcher notificationDispatcher,
                                           @Lazy MailService mailService) {
        this.jobManager = jobManager;
        this.dashboardReportService = dashboardReportService;
        this.notificationDispatcher = notificationDispatcher;
        this.mailService = mailService;
    }

    @PreDestroy
    public void stop() {
        renderExecutor.shutdownNow();
    }

    @Override
    public void executeReport(TenantId tenantId, SchedulerEvent event) {
        log.info("[{}] Executing report for scheduler event [{}][{}]", tenantId, event.getId(), event.getName());
        try {
            ReportConfig cfg = JacksonUtil.treeToValue(event.getConfiguration(), ReportConfig.class);
            ReportJobConfiguration jobCfg = ReportJobConfiguration.builder()
                    .reportTemplateId(cfg.getReportTemplateId())
                    .userId(cfg.getUserId())
                    .timezone(cfg.getTimezone())
                    .targets(cfg.getTargets())
                    .notificationTemplateId(cfg.getNotificationTemplateId())
                    .schedulerEventInfo(new EntityInfo(event.getId(), event.getName()))
                    .build();
            jobManager.submitJob(new Job(tenantId, JobType.REPORT, event.getId().toString(), cfg.getReportTemplateId(), jobCfg));
        } catch (Exception e) {
            log.error("[{}] Failed to submit report job for scheduler event [{}]", tenantId, event.getId(), e);
            // The engine's per-event try/catch (DefaultSchedulerService#processEvent) logs and
            // continues on any exception, so rethrowing here (rather than swallowing) is what makes
            // the failure visible instead of silently vanishing after the log line above.
            throw new RuntimeException(e);
        }
    }

    /**
     * Renders a {@code generateDashboardReport} event's dashboard directly, then delivers it through
     * the same {@link ReportNotificationDispatcher} the templated-report path uses.
     * <p>
     * The event's {@code configuration} is PE's generic scheduler shape — {@code {msgType, msgBody,
     * metadata}} — with the dashboard settings under {@code msgBody.reportConfig}. Recipients are read
     * from {@code targets} / {@code notificationTemplateId} alongside {@code msgBody}: PE has no such
     * fields (it expects a rule chain's email node to address the report), so a legacy PE-era event
     * carries neither and the dispatcher logs exactly why nothing was delivered.
     */
    @Override
    public void executeDashboardReport(TenantId tenantId, SchedulerEvent event) {
        log.info("[{}] Executing dashboard report for scheduler event [{}][{}]", tenantId, event.getId(), event.getName());
        JsonNode configuration = event.getConfiguration();
        DashboardReportConfig reportConfig = readDashboardReportConfig(configuration);
        List<UUID> targets = readTargets(configuration);
        NotificationTemplateId notificationTemplateId = readNotificationTemplateId(configuration);
        String context = event.getId().toString();

        // Submitted, not run: see renderExecutor's note - the caller is the single scheduler thread.
        renderExecutor.execute(() -> {
            try {
                Report report = dashboardReportService.generateAndSaveReport(tenantId, reportConfig);
                // PE's own delivery: msgBody.sendEmail + msgBody.emailConfig{from,to,subject,body}.
                // Honouring it here is what lets a PE-authored event deliver with no rule chain at all.
                sendEmailIfConfigured(tenantId, configuration, report, context);
                // Our addition, for events configured through this fork's UI.
                notificationDispatcher.dispatch(tenantId, report, targets, notificationTemplateId, context);
            } catch (Exception e) {
                // Nothing above us can catch this - we are already off the scheduler thread, and the
                // engine re-armed the next occurrence before this task ever started running.
                log.error("[{}][{}] Failed to generate dashboard report for scheduler event '{}'",
                        tenantId, event.getId(), event.getName(), e);
            }
        });
    }

    private static DashboardReportConfig readDashboardReportConfig(JsonNode configuration) {
        JsonNode msgBody = configuration != null ? configuration.get("msgBody") : null;
        JsonNode reportConfig = msgBody != null ? msgBody.get("reportConfig") : null;
        DashboardReportConfig config = JacksonUtil.treeToValue(reportConfig, DashboardReportConfig.class);
        if (config == null) {
            throw new IllegalArgumentException("Scheduler event does not contain msgBody.reportConfig");
        }
        return config;
    }

    private static List<UUID> readTargets(JsonNode configuration) {
        JsonNode targets = configuration != null ? configuration.get("targets") : null;
        if (targets == null || !targets.isArray()) {
            return List.of();
        }
        return JacksonUtil.convertValue(targets, new TypeReference<List<UUID>>() {});
    }

    private static NotificationTemplateId readNotificationTemplateId(JsonNode configuration) {
        JsonNode templateId = configuration != null ? configuration.get("notificationTemplateId") : null;
        return templateId == null || templateId.isNull()
                ? null : JacksonUtil.treeToValue(templateId, NotificationTemplateId.class);
    }

    /**
     * PE-shaped delivery for {@code generateDashboardReport}: {@code msgBody.sendEmail} plus {@code
     * msgBody.emailConfig} {@code {from, to, subject, body}} (verified in {@code ui-ngx-4.2.0PE.jar},
     * chunk-LQGPECC4.js — PE's default subject is {@code "Report generated on %d{yyyy-MM-dd HH:mm:ss}"}).
     * PE relies on a rule chain to turn that into an actual email; sending it here is what removes the
     * chain requirement. Subject and body go through the same {@code %d{...}} substitution PE applies
     * to report names.
     * <p>
     * Best-effort: a mail failure is logged, never rethrown — the report is already rendered and
     * stored, and the notification-target path below it must still get its chance.
     */
    private void sendEmailIfConfigured(TenantId tenantId, JsonNode configuration, Report report, String context) {
        JsonNode msgBody = configuration != null ? configuration.get("msgBody") : null;
        if (msgBody == null || !msgBody.path("sendEmail").asBoolean(false)) {
            return;
        }
        JsonNode emailConfig = msgBody.get("emailConfig");
        if (emailConfig == null || emailConfig.isNull()) {
            log.warn("[{}][{}] Scheduler event has sendEmail enabled but no emailConfig; report [{}] not emailed",
                    tenantId, context, report.getId());
            return;
        }
        String to = emailConfig.path("to").asText(null);
        if (StringUtils.isBlank(to)) {
            log.warn("[{}][{}] Scheduler event's emailConfig has no recipient; report [{}] not emailed",
                    tenantId, context, report.getId());
            return;
        }
        Date now = new Date();
        String timezone = readDashboardReportConfig(configuration).getTimezone();
        try {
            mailService.send(tenantId, report.getCustomerId(), TbEmail.builder()
                    .from(emailConfig.path("from").asText(null))
                    .to(to)
                    .cc(emailConfig.path("cc").asText(null))
                    .bcc(emailConfig.path("bcc").asText(null))
                    .subject(ReportUtils.prepareReportName(emailConfig.path("subject").asText(null), now, timezone))
                    .body(ReportUtils.prepareReportName(emailConfig.path("body").asText(null), now, timezone))
                    .reports(List.of(report.getId()))
                    .build());
            log.info("[{}][{}] Emailed report [{}] to {}", tenantId, context, report.getId(), to);
        } catch (Exception e) {
            log.error("[{}][{}] Failed to email report [{}]", tenantId, context, report.getId(), e);
        }
    }

}
