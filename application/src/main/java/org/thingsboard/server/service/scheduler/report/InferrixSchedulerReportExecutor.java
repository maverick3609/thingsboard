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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.JobManager;
import org.thingsboard.server.common.data.EntityInfo;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.report.ReportConfig;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;

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

    public InferrixSchedulerReportExecutor(JobManager jobManager) {
        this.jobManager = jobManager;
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
            jobManager.submitJob(new Job(tenantId, JobType.REPORT, event.getId().toString(), event.getId(), jobCfg));
        } catch (Exception e) {
            log.error("[{}] Failed to submit report job for scheduler event [{}]", tenantId, event.getId(), e);
            // The engine's per-event try/catch (DefaultSchedulerService#processEvent) logs and
            // continues on any exception, so rethrowing here (rather than swallowing) is what makes
            // the failure visible instead of silently vanishing after the log line above.
            throw new RuntimeException(e);
        }
    }

}
