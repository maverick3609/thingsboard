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
package org.thingsboard.server.common.data.job.task;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.report.Report;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ToString(callSuper = true)
public class ReportTaskResult extends TaskResult {

    private Report report;
    private String error;

    @Builder
    private ReportTaskResult(boolean success, boolean discarded, Report report, String error) {
        super(success, discarded);
        this.report = report;
        this.error = error;
    }

    public static ReportTaskResult success(ReportTask task, Report report) {
        return ReportTaskResult.builder()
                .success(true)
                .report(report)
                .build();
    }

    public static ReportTaskResult failed(ReportTask task, Throwable error) {
        return ReportTaskResult.builder()
                .error(error.getMessage())
                .build();
    }

    public static ReportTaskResult discarded(ReportTask task) {
        return ReportTaskResult.builder()
                .discarded(true)
                .build();
    }

    @Override
    public JobType getJobType() {
        return JobType.REPORT;
    }

    @Override
    public String getError() {
        return error;
    }

}
