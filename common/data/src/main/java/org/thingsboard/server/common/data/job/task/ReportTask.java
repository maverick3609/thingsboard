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

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@ToString(callSuper = true)
public class ReportTask extends Task<ReportTaskResult> {

    private CustomerId customerId;
    private ReportTemplateId reportTemplateId;
    private ReportTemplateConfig reportTemplateConfig; // R2a: typed PE-shape config (was jsonb passthrough)
    private String timezone;
    private UserId userId;
    private EntityId userOwnerId;
    private String accessToken;
    private long accessTokenExpirationTs;
    private EntityId originator;

    @Override
    public ReportTaskResult toFailed(Throwable error) {
        return ReportTaskResult.failed(this, error);
    }

    @Override
    public ReportTaskResult toDiscarded() {
        return ReportTaskResult.discarded(this);
    }

    @Override
    public EntityId getEntityId() {
        return getJobId();
    }

    @Override
    public JobType getJobType() {
        return JobType.REPORT;
    }

}
