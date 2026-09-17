// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.report.configuration.style.Insets;

@Schema
@Data
@NoArgsConstructor
public abstract class AbstractLayoutReportComponent implements LayoutReportComponent {

    private Insets margins;
    private Insets paddings;
    private String background;
    private Integer borderWidth;
    private Integer borderRadius;
    private String borderColor;
}
