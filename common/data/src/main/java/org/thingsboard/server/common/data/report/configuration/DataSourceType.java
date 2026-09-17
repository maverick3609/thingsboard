// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
