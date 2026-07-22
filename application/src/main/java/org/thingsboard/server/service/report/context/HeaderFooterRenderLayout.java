/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
