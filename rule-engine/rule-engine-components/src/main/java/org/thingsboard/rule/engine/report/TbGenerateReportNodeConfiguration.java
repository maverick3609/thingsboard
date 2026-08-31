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
package org.thingsboard.rule.engine.report;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;

/**
 * Config for {@link TbGenerateReportNode} — PE's {@code TbGenerateReportNodeConfiguration}, minus
 * its two external-render-server fields.
 * <p>
 * <b>Deviation from PE.</b> PE carries {@code useSystemReportsServer} + {@code
 * reportsServerEndpointUrl} because it renders through a separate {@code tb-web-report} HTTP
 * service and lets each node point at a different one. This fork renders in-process with Playwright
 * ({@code PlaywrightWebReportRenderer}, design spec §6.1), so there is no endpoint to choose and
 * both fields would be dead config that silently does nothing. Dropped rather than accepted-and-ignored.
 * <p>
 * {@code useReportConfigFromMessage} defaults to {@code true}: the dominant producer is a
 * {@code generateDashboardReport} scheduler event, which carries its own per-event
 * {@link DashboardReportConfig} in the message body. A statically-configured {@link #reportConfig}
 * is for chains that generate the same dashboard report on every message.
 */
@Data
public class TbGenerateReportNodeConfiguration implements NodeConfiguration<TbGenerateReportNodeConfiguration> {

    private boolean useReportConfigFromMessage;
    private DashboardReportConfig reportConfig;

    @Override
    public TbGenerateReportNodeConfiguration defaultConfiguration() {
        TbGenerateReportNodeConfiguration configuration = new TbGenerateReportNodeConfiguration();
        configuration.setUseReportConfigFromMessage(true);
        configuration.setReportConfig(null);
        return configuration;
    }

}
