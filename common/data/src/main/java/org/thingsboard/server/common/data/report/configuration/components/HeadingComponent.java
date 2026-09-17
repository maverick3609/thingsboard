// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class HeadingComponent extends AbstractDataWithLayoutReportComponent {

    private String value;
    private Font font;
    private String color;
    private TextAlignment textAlignment;
    private VerticalAlignment verticalAlignment;
    private Integer height;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.HEADING;
    }
}
