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
