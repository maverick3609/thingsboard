// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.BaseData;
import org.thingsboard.server.common.data.HasCustomerId;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;

import java.util.UUID;

/**
 * Persisted report metadata. The rendered bytes are NOT a bean field here — they live in the
 * DAO's separate {@code data} column (see the reporting design spec §4) and are exposed via
 * {@link ReportData} at render time / DAO read time.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Report extends BaseData<ReportId> implements HasTenantId, HasCustomerId, HasName {

    @NotNull
    private TenantId tenantId;
    private CustomerId customerId;
    private UUID templateId;
    @NotNull
    private TbReportFormat format;
    @NotBlank
    private String name;
    @NotNull
    private UserId userId;

    public Report() {
        super();
    }

    public Report(ReportId id) {
        super(id);
    }

    public Report(Report report) {
        super(report);
        this.tenantId = report.getTenantId();
        this.customerId = report.getCustomerId();
        this.templateId = report.getTemplateId();
        this.name = report.getName();
        this.format = report.getFormat();
        this.userId = report.getUserId();
    }

}
