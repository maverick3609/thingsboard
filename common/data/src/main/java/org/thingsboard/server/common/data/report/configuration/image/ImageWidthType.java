// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.image;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ImageWidthType {
    FIT_WIDTH("fitWidth"),
    ORIGINAL("original"),
    CUSTOM("custom");

    private final String value;

    ImageWidthType(String label) {
        this.value = label;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }

    @JsonCreator
    public static ImageWidthType fromLabel(String value) {
        for (ImageWidthType type : ImageWidthType.values()) {
            if (!type.value.equalsIgnoreCase(value)) continue;
            return type;
        }
        throw new IllegalArgumentException("Unknown ImageWidthType: " + value);
    }
}
