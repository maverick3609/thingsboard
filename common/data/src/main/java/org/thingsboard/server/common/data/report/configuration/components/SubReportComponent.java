// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.thingsboard.server.common.data.id.ReportTemplateId;

@Schema
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SubReportComponent extends AbstractDataReportComponent {

    private ReportTemplateId templateId;
    private boolean avoidPageBreakInside;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.SUB_REPORT;
    }
}
