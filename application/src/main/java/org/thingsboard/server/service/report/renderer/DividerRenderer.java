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
import org.thingsboard.server.common.data.report.configuration.components.DividerComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.style.BorderLength;
import org.thingsboard.server.common.data.report.configuration.style.BorderType;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ColorUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.HashMap;

/**
 * Renders a {@code DIVIDER} component (PE {@code report.renderer.DividerRenderer}): a horizontal rule via
 * {@code html/components/divider-template} whose width (full / half), thickness, colour and border style
 * (solid / dashed / dotted) come from the component.
 */
@Component
public class DividerRenderer extends ReportComponentWithLayoutRenderer<DividerComponent> {

    @Override
    protected String renderContent(DividerComponent component, ComponentData componentData) {
        HashMap<String, Object> componentVariables = new HashMap<>();
        componentVariables.put("width", component.getLength() != null && component.getLength() == BorderLength.SHORT ? "50%" : "100%");
        String borderWidth = component.getWidthPx() != null ? component.getWidthPx() + "px" : "1px";
        String borderColor = component.getColor() != null ? ColorUtils.normalizeCssColor(component.getColor()) : "#000";
        String borderType = component.getBorderType() != null ? component.getBorderType().getValue() : BorderType.SOLID.getValue();
        componentVariables.put("borderStyle", borderWidth + " " + borderType + " " + borderColor);
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/divider-template", componentVariables);
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.DIVIDER;
    }
}
