// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.image;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ImageAlignment {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right");

    private final String value;

    ImageAlignment(String label) {
        this.value = label;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }

    @JsonCreator
    public static ImageAlignment fromLabel(String value) {
        for (ImageAlignment type : ImageAlignment.values()) {
            if (!type.value.equalsIgnoreCase(value)) continue;
            return type;
        }
        throw new IllegalArgumentException("Unknown ImageAlignment: " + value);
    }
}
