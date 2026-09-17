// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.report.configuration.style.BorderLength;
import org.thingsboard.server.common.data.report.configuration.style.BorderType;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DividerComponent extends AbstractLayoutReportComponent {

    private BorderLength length;
    private BorderType borderType;
    private Integer widthPx;
    private String color;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.DIVIDER;
    }
}
