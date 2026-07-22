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
