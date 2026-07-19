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

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.rule.engine.api.NotificationCenter;
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
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestConfig;
import org.thingsboard.server.common.data.notification.info.ReportGeneratedNotificationInfo;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.util.CollectionsUtil;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.job.JobProcessor;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.List;
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
 * OwnersCacheService#getOwner} (replaced with a direct customer-id-else-tenant-id resolution). The
 * rule-node {@code outputTbMsgProto} result push (PE's V2 scheduler-originated path) is also R2.
 * <p>
 * Deliberately does NOT depend on {@link TbReportService} — only {@code
 * org.thingsboard.server.service.report.task.ReportTaskProcessor} renders/persists the report —
 * so this bean can wire unconditionally, unlike {@code TbReportService} which is gated behind
 * {@code reports.renderer.enabled}.
 */
@Component
@Slf4j
public class ReportJobProcessor implements JobProcessor {

    private final ReportTemplateService reportTemplateService;
    private final UserService userService;
    private final SystemSecurityService systemSecurityService;
    private final JwtTokenFactory jwtTokenFactory;
    private final NotificationCenter notificationCenter;

    public ReportJobProcessor(ReportTemplateService reportTemplateService,
                               UserService userService,
                               SystemSecurityService systemSecurityService,
                               JwtTokenFactory jwtTokenFactory,
                               @Lazy NotificationCenter notificationCenter) {
        this.reportTemplateService = reportTemplateService;
        this.userService = userService;
        this.systemSecurityService = systemSecurityService;
        this.jwtTokenFactory = jwtTokenFactory;
        this.notificationCenter = notificationCenter;
    }

    @Override
    public int process(Job job, Consumer<Task<?>> taskConsumer) throws Exception {
        TenantId tenantId = job.getTenantId();
        ReportJobConfiguration configuration = job.getConfiguration();
        log.info("[{}][{}] Processing report job for template [{}]", tenantId, job.getId(), configuration.getReportTemplateId());

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
        if (job.getStatus() != JobStatus.COMPLETED) {
            return;
        }
        TenantId tenantId = job.getTenantId();
        ReportJobConfiguration configuration = job.getConfiguration();
        ReportJobResult result = (ReportJobResult) job.getResult();
        Report report = result.getReport();
        if (report == null) {
            log.warn("[{}][{}] Report job completed without a report result, skipping notification", tenantId, job.getId());
            return;
        }
        log.info("[{}][{}] Report job finished, report [{}]", tenantId, job.getId(), report.getId());

        if (CollectionsUtil.isNotEmpty(configuration.getTargets()) && configuration.getNotificationTemplateId() != null) {
            NotificationRequestConfig requestConfig = new NotificationRequestConfig();
            requestConfig.setReports(List.of(report.getId()));
            NotificationRequest notificationRequest = NotificationRequest.builder()
                    .tenantId(tenantId)
                    .targets(configuration.getTargets())
                    .templateId(configuration.getNotificationTemplateId())
                    .originatorEntityId(report.getUserId())
                    .info(ReportGeneratedNotificationInfo.builder()
                            .tenantId(tenantId)
                            .customerId(report.getCustomerId())
                            .reportFormat(report.getFormat())
                            .reportName(report.getName())
                            .userId(report.getUserId())
                            .build())
                    .additionalConfig(requestConfig)
                    .build();
            try {
                notificationCenter.processNotificationRequest(tenantId, notificationRequest, null);
                log.info("[{}][{}] Dispatched report-generated notification for report [{}]", tenantId, job.getId(), report.getId());
            } catch (Exception e) {
                log.error("[{}][{}] Failed to dispatch report-generated notification for report [{}]", tenantId, job.getId(), report.getId(), e);
            }
        }
    }

    @Override
    public JobType getType() {
        return JobType.REPORT;
    }

}
