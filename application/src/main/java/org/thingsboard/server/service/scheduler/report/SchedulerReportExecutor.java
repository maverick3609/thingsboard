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
 * Inferrix seam for the two report-producing scheduler event types (spec §11 #4). The bean is
 * renderer-gated, so {@code DefaultSchedulerService} holds it as an {@code Optional} and behaves
 * sensibly when the renderer is off.
 * <p>
 * Both methods must return promptly: {@code DefaultSchedulerService} fires events on a
 * SINGLE-THREADED scheduled executor, so anything that blocks here stalls every other scheduler
 * event on the node. Implementations offload the actual work.
 */
public interface SchedulerReportExecutor {

    /** {@code generateReport} — a report-template report, submitted to the job framework. */
    void executeReport(TenantId tenantId, SchedulerEvent event);

    /**
     * {@code generateDashboardReport} — renders the dashboard named by the event's
     * {@code msgBody.reportConfig} directly, with no rule-chain hop. PE routes this type through the
     * rule engine (Message Type Switch -> "generate dashboard report" node), which means it does
     * nothing at all until a tenant wires that chain; this seam is the zero-configuration path.
     */
    void executeDashboardReport(TenantId tenantId, SchedulerEvent event);
}
