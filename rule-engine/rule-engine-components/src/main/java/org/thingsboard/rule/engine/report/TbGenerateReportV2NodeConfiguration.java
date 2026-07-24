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
import org.thingsboard.server.common.data.report.ReportConfig;

/**
 * Config for {@link TbGenerateReportV2Node} (PE-verbatim shape).
 * <p>
 * {@code useConfigFromMessage=false} (default): the static {@link #config} on the node is used.
 * {@code useConfigFromMessage=true}: the {@link ReportConfig} is parsed from the incoming message
 * body instead — see the {@code userId} provenance note in {@link TbGenerateReportV2Node#onMsg}.
 */
@Data
public class TbGenerateReportV2NodeConfiguration implements NodeConfiguration<TbGenerateReportV2NodeConfiguration> {

    private boolean useConfigFromMessage;
    private ReportConfig config;

    @Override
    public TbGenerateReportV2NodeConfiguration defaultConfiguration() {
        return new TbGenerateReportV2NodeConfiguration();
    }

}
