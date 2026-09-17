// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;

@Data
@NoArgsConstructor
public class CellSettings {

    private Font font;
    private String color;
    private String backgroundColor;
    private TextAlignment textAlignment;
    private VerticalAlignment verticalAlignment;
}
