// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;

/**
 * Renders an {@code ALARM_TABLE} to CSV (PE {@code report.renderer.CsvAlarmTableRenderer}): one row per alarm,
 * columns from the alarm source's data keys — layout is the {@link AbstractCsvComponentRenderer} base. The
 * alarm rows come from the engine's {@code buildAlarmComponentData}, whose read is scoped to the
 * permission-filtered entity ids (F1 finding #1), never to ids from config. Plain {@code @Component}: stateless,
 * injects nothing, boots renderer-off, collected only by the gated {@code CsvReportService}.
 */
@Component
public class CsvAlarmTableRenderer extends AbstractCsvComponentRenderer<AlarmTableComponent> {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ALARM_TABLE;
    }

}
