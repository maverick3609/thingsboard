// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.job;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.job.task.ReportTaskResult;
import org.thingsboard.server.common.data.job.task.TaskResult;
import org.thingsboard.server.common.data.report.Report;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ReportJobResult extends JobResult {

    private Report report;

    @Override
    public void processTaskResult(TaskResult taskResult) {
        super.processTaskResult(taskResult);
        ReportTaskResult reportTaskResult = (ReportTaskResult) taskResult;
        if (reportTaskResult.getReport() != null) {
            this.report = reportTaskResult.getReport();
        }
    }

    @Override
    public JobType getJobType() {
        return JobType.REPORT;
    }

}
