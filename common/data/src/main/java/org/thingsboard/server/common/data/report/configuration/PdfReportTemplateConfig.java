// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.style.Insets;
import org.thingsboard.server.common.data.report.configuration.style.PageOrientation;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;

@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@SuperBuilder
public class PdfReportTemplateConfig extends AbstractReportTemplateConfig {

    private PageSize pageSize;
    private PageOrientation pageOrientation;
    private Insets pageMargins;
    private String pageBackground;
    private HeaderFooter header;
    private HeaderFooter footer;

    @Override
    public TbReportFormat getFormat() {
        return TbReportFormat.PDF;
    }
}
