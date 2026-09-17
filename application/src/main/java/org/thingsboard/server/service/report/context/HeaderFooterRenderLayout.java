// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.context;

import lombok.Data;

/**
 * Measured running header/footer (PE {@code report.context.HeaderFooterRenderLayout}). Produced by
 * {@code PdfReportService.renderHeaderFooter}: the pre-rendered HTML plus its measured pixel height (via
 * {@code HtmlRenderUtils.measureHtmlHeight}), which the assembler turns into the {@code @page} top/bottom
 * margins so body content never overlaps the running region. Carries a separate {@code firstPage} variant
 * for templates whose first page differs.
 */
@Data
public class HeaderFooterRenderLayout {

    private String htmlContent;
    private String firstPageHtmlContent;
    private boolean enabled;
    private boolean firstPageEnabled;
    private int heightPx;
    private int firstPageHeightPx;

}
