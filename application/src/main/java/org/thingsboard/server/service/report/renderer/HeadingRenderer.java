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
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ColorUtils;
import org.thingsboard.server.service.report.util.FontUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.HashMap;

/**
 * Renders a {@code HEADING} component (PE {@code report.renderer.HeadingRenderer}): a single styled text
 * line (colour, font, alignment, fixed or auto height) via {@code html/components/heading-template}. The
 * value is first run through {@link ThymeleafUtil#renderFromHtmlString} so {@code ${...}} placeholders
 * interpolate from {@link ComponentData#getVariables()} (server-side data binding is R2b — the map is
 * empty until then, so static headings render verbatim).
 */
@Component
public class HeadingRenderer extends ReportComponentWithLayoutRenderer<HeadingComponent> {

    @Override
    public String renderContent(HeadingComponent component, ComponentData componentData) {
        String processedText = ThymeleafUtil.renderFromHtmlString(component.getValue(), componentData.getVariables());
        Font font = component.getFont() != null ? component.getFont() : new Font();
        FontWeight weight = font.getWeight() != null ? font.getWeight() : FontWeight.NORMAL;
        FontStyle style = font.getStyle() != null ? font.getStyle() : FontStyle.NORMAL;
        HashMap<String, Object> templateVars = new HashMap<>();
        templateVars.put("color", resolveColor(component));
        templateVars.put("fontSize", resolveFontSize(font));
        templateVars.put("fontWeight", weight.getValue());
        templateVars.put("fontStyle", style.getValue());
        templateVars.put("fontFamily", FontUtils.resolveFontFamily(font.getFamily()));
        templateVars.put("textAlignment", resolveTextAlignment(component));
        templateVars.put("verticalAlignment", resolveVerticalAlignment(component));
        templateVars.put("height", resolveHeight(component));
        templateVars.put("value", processedText);
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/heading-template", templateVars);
    }

    private String resolveColor(HeadingComponent component) {
        return component.getColor() != null ? ColorUtils.normalizeCssColor(component.getColor()) : "#000";
    }

    private Float resolveFontSize(Font font) {
        Float size = font.getSize();
        return size != null && size > 0.0f ? size : 10.0f;
    }

    private String resolveTextAlignment(HeadingComponent component) {
        TextAlignment alignment = component.getTextAlignment();
        return (alignment != null ? alignment : TextAlignment.CENTER).getValue();
    }

    private String resolveVerticalAlignment(HeadingComponent component) {
        VerticalAlignment vertical = component.getVerticalAlignment();
        return (vertical != null ? vertical : VerticalAlignment.MIDDLE).getValue();
    }

    private String resolveHeight(HeadingComponent component) {
        Integer height = component.getHeight();
        return height != null && height > 0 ? height + "pt" : "100%";
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.HEADING;
    }
}
