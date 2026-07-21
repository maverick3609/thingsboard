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
package org.thingsboard.server.service.report.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.job.task.ReportTaskResult;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.queue.task.TaskProcessor;
import org.thingsboard.server.service.report.TbReportService;

/**
 * PE's report task processor, ported to CE's {@link TaskProcessor} shape (mirrors {@code
 * org.thingsboard.server.service.job.task.DummyTaskProcessor}). Renders and persists the report
 * ({@link TbReportService#generateReport}) for each {@link ReportTask} the {@code
 * org.thingsboard.server.service.report.ReportJobProcessor} submits, auto-discovered by {@code
 * DefaultJobManager} via {@link #getJobType()}.
 * <p>
 * Guarded by the same {@code reports.renderer.enabled} property as {@link TbReportService}: this
 * class depends on it directly, so without the guard Spring would fail to wire this bean whenever
 * the (disabled-by-default) renderer isn't opted into.
 */
@Component
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class ReportTaskProcessor extends TaskProcessor<ReportTask, ReportTaskResult> {

    private final TbReportService tbReportService;

    @Value("${reports.generation_timeout_ms:120000}")
    private long generationTimeoutMs = 120000;

    // @Lazy breaks a Spring bean cycle that only forms when the renderer is enabled: DefaultTbServiceInfoProvider
    // eagerly collects every TaskProcessor (List<TaskProcessor>), which pulls in this bean, whose TbReportService ->
    // DefaultDashboardReportService -> SystemSecurityService -> MailService -> TbApiUsageReportClient -> HashPartitionService
    // chain loops back to DefaultTbServiceInfoProvider ("Unable to start web server"). Deferring TbReportService to first
    // process() call (task time, long after context start) severs the cycle at the only reporting edge on it.
    public ReportTaskProcessor(@Lazy TbReportService tbReportService) {
        this.tbReportService = tbReportService;
    }

    @Override
    public ReportTaskResult process(ReportTask task) throws Exception {
        Report report = tbReportService.generateReport(task);
        return ReportTaskResult.success(task, report);
    }

    @Override
    public long getProcessingTimeout(ReportTask task) {
        return generationTimeoutMs;
    }

    @Override
    public JobType getJobType() {
        return JobType.REPORT;
    }

}
