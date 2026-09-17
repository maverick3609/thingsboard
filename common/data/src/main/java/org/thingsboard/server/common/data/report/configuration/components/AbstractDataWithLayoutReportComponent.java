// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.thingsboard.server.common.data.report.configuration.style.Insets;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
public abstract class AbstractDataWithLayoutReportComponent extends AbstractDataReportComponent implements LayoutReportComponent {

    private Insets margins;
    private Insets paddings;
    private String background;
    private Integer borderWidth;
    private Integer borderRadius;
    private String borderColor;
}
