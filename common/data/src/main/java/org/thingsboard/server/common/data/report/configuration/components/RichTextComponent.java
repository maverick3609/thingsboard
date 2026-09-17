// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RichTextComponent extends AbstractDataWithLayoutReportComponent {

    private String value;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.RICH_TEXT;
    }
}
