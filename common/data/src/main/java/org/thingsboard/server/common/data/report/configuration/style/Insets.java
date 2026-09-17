// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.style;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Insets {

    private int left;
    private int right;
    private int top;
    private int bottom;

    public Insets(int margin) {
        this.left = margin;
        this.top = margin;
        this.right = margin;
        this.bottom = margin;
    }
}
