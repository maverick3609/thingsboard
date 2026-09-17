// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.notification.info;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.TbReportFormat;

import java.util.Map;

import static org.thingsboard.server.common.data.util.CollectionsUtil.mapOf;

/**
 * PE {@code ReportGeneratedNotificationInfo} (ported field-for-field from a CFR decompile of the
 * PE jar's compiled class — no {@code .java} source was staged for this file). Carried by the
 * {@link org.thingsboard.server.common.data.notification.NotificationRequest} that {@code
 * ReportJobProcessor} dispatches once a scheduled report job completes (design spec, reporting
 * task 15). Purely additive: {@link NotificationInfo} resolves polymorphic subtypes by fully
 * qualified class name ({@code @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)}), so this class
 * self-registers with no {@code @JsonSubTypes} edit anywhere.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReportGeneratedNotificationInfo implements RuleOriginatedNotificationInfo {

    private TenantId tenantId;
    private CustomerId customerId;
    private TbReportFormat reportFormat;
    private String reportName;
    private UserId userId;

    @Override
    public Map<String, String> getTemplateData() {
        return mapOf(
                "reportFormat", reportFormat.name(),
                "reportName", reportName
        );
    }

    @Override
    public TenantId getAffectedTenantId() {
        return tenantId;
    }

    @Override
    public CustomerId getAffectedCustomerId() {
        return customerId;
    }

    @Override
    public UserId getAffectedUserId() {
        return userId;
    }

}
