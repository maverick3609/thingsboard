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
