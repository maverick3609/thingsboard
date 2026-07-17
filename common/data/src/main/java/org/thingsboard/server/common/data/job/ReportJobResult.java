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
