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
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.RichTextComponent;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

/**
 * Renders a {@code RICH_TEXT} component (PE {@code report.renderer.RichTextRenderer}): the designer's raw
 * rich-text HTML, run through {@link ThymeleafUtil#renderFromTextString} so {@code ${...}} placeholders
 * interpolate from {@link ComponentData#getVariables()} (server-side data binding is R2b — the map is
 * empty until then, so static rich text renders verbatim) and wrapped by the layout base.
 */
@Component
public class RichTextRenderer extends ReportComponentWithLayoutRenderer<RichTextComponent> {

    @Override
    protected String renderContent(RichTextComponent component, ComponentData componentData) {
        return ThymeleafUtil.renderFromTextString(component.getValue(), componentData.getVariables());
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.RICH_TEXT;
    }
}
