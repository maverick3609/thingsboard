// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
