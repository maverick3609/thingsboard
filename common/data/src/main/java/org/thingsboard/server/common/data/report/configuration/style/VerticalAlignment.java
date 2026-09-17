// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.style;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum VerticalAlignment {
    BOTTOM("bottom"),
    TOP("top"),
    MIDDLE("middle");

    private final String value;

    VerticalAlignment(String label) {
        this.value = label;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }

    @JsonCreator
    public static VerticalAlignment fromLabel(String value) {
        for (VerticalAlignment type : VerticalAlignment.values()) {
            if (!type.value.equalsIgnoreCase(value)) continue;
            return type;
        }
        throw new IllegalArgumentException("Unknown VerticalAlignment: " + value);
    }
}
