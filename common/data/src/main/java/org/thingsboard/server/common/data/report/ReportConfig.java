// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.thingsboard.server.common.data.id.NotificationTemplateId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.UserId;

import java.util.List;
import java.util.UUID;

/**
 * Scheduler {@code generateReport} event config (also used as the REST-facing shape for the
 * same fields). Field names are PE-verbatim (design spec §2.3) — the scheduler
 * {@code generateReport} form and downstream job processing depend on them.
 */
@Data
public class ReportConfig {

    @NotNull
    private ReportTemplateId reportTemplateId;
    @NotNull
    private UserId userId;
    private String timezone;
    private List<UUID> targets;
    private NotificationTemplateId notificationTemplateId;

}
