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
package org.thingsboard.server.service.scheduler.report;

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;

/**
 * Inferrix seam for 'generateReport' scheduler events (spec §11 #4).
 * PE submits a report Job here; CE has no report subsystem yet.
 * The inferrix-reporting module contributes the real bean when it is
 * forward-ported; until then the engine logs a WARN when no bean is present.
 */
public interface SchedulerReportExecutor {

    void executeReport(TenantId tenantId, SchedulerEvent event);
}
