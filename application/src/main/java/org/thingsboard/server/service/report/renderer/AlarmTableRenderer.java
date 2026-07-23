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

import java.util.HashMap;
import java.util.Map;

/**
 * Renders an {@code ALARM_TABLE} (PE {@code report.renderer.AlarmTableRenderer}): alarm rows resolved by the
 * engine's {@code buildAlarmComponentData} (whose entity ids come only from the permission-scoped
 * {@code fetchEntities}). Adds alarm-specific column defaults on top of the {@link
 * TableWithLayoutComponentRenderer} base: a colour + bold weight keyed off the (bounded, enum) severity value,
 * and a human-readable status label. These steer <b>style</b> and a display <b>value</b> only — the cell text
 * is still escaped by the template, and the severity→colour map keys off a fixed alarm-severity enum, never
 * free text.
 */
@Component
public class AlarmTableRenderer extends TableWithLayoutComponentRenderer<AlarmTableComponent> {

    private static final Map<String, String> SEVERITY_COLOR = new HashMap<>();
    private static final Map<String, String> DISPLAY_STATUS = new HashMap<>();

    static {
        SEVERITY_COLOR.put("CRITICAL", "red");
        SEVERITY_COLOR.put("MAJOR", "orange");
        SEVERITY_COLOR.put("MINOR", "#ffca3d");
        SEVERITY_COLOR.put("WARNING", "#abab00");
        SEVERITY_COLOR.put("INDETERMINATE", "green");
        DISPLAY_STATUS.put("ACTIVE_UNACK", "Active Unacknowledged");
        DISPLAY_STATUS.put("ACTIVE_ACK", "Active Acknowledged");
        DISPLAY_STATUS.put("CLEARED_UNACK", "Cleared Unacknowledged");
        DISPLAY_STATUS.put("CLEARED_ACK", "Cleared Acknowledged");
    }

    @Override
    protected String dataSourceName() {
        return "alarm source";
    }

    @Override
    protected String noDataMessage() {
        return "No alarms found";
    }

    @Override
    protected String defaultFontWeight(String key) {
        return "severity".equals(key) ? "bold" : null;
    }

    @Override
    protected String defaultColor(String key, String value) {
        return "severity".equals(key) ? SEVERITY_COLOR.getOrDefault(value, value) : null;
    }

    @Override
    protected String defaultValue(String key, String value) {
        return "status".equals(key) ? DISPLAY_STATUS.getOrDefault(value, value) : super.defaultValue(key, value);
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ALARM_TABLE;
    }
}
