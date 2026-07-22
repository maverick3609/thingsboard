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
package org.thingsboard.server.common.data.report.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DataSourceType {
    DEVICE("device"),
    ENTITY("entity"),
    ENTITY_COUNT("entityCount"),
    ALARM_COUNT("alarmCount");

    private final String label;

    DataSourceType(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return this.label;
    }

    @JsonCreator
    public static DataSourceType fromLabel(String value) {
        for (DataSourceType type : DataSourceType.values()) {
            if (!type.label.equalsIgnoreCase(value)) continue;
            return type;
        }
        throw new IllegalArgumentException("Unknown DataSourceType: " + value);
    }
}
