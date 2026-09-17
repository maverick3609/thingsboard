// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.components.PageBreakComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.service.report.context.ComponentData;

/**
 * Renders a {@code PAGE_BREAK} component (PE {@code report.renderer.PageBreakRenderer}): a {@code
 * page-break} div (the CSS rule that forces the next component onto a new page lives in the report
 * stylesheet). Not a layout component — emits its marker directly.
 */
@Component
public class PageBreakRenderer implements PdfReportComponentRenderer<PageBreakComponent> {

    @Override
    public String render(PageBreakComponent component, ComponentData componentData) {
        return "<div class=\"page-break\"></div>";
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.PAGE_BREAK;
    }
}
