// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
