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

import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.report.configuration.components.LayoutReportComponent;
import org.thingsboard.server.common.data.report.configuration.style.Insets;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ColorUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Base for every renderer whose component carries layout styling (PE {@code report.renderer
 * .ReportComponentWithLayoutRenderer}). Renders the subclass's inner fragment ({@link #renderContent})
 * then wraps it in {@code html/components/component-layout} — applying the component's margins, paddings,
 * background and border. Also shortcuts to an error box when the {@link ComponentData} carries an error.
 * <p>
 * {@link #layoutWidthPx} is the inner content width (usable page width minus this component's horizontal
 * margins + paddings, converted pt→px), exposed to subclasses that must size inner content (e.g. images).
 *
 * @param <C> the concrete layout-bearing component type
 */
public abstract class ReportComponentWithLayoutRenderer<C extends LayoutReportComponent>
        implements PdfReportComponentRenderer<C> {

    protected int layoutWidthPx;

    @Override
    public String render(C component, ComponentData componentData) {
        if (StringUtils.isNotBlank(componentData.getError())) {
            return ThymeleafUtil.renderFromHtmlTemplate("html/components/error-template", Map.of("errorMessage", componentData.getError()));
        }
        Insets margins = component.getMargins() != null ? component.getMargins() : new Insets(0);
        Insets paddings = component.getPaddings() != null ? component.getPaddings() : new Insets(0);
        this.layoutWidthPx = componentData.getUsablePageWidthPx();
        this.layoutWidthPx = (int) ((float) this.layoutWidthPx - (float) (margins.getLeft() + margins.getRight()) * 4.0f / 3.0f);
        this.layoutWidthPx = (int) ((float) this.layoutWidthPx - (float) (paddings.getLeft() + paddings.getRight()) * 4.0f / 3.0f);
        String content = renderContent(component, componentData);
        HashMap<String, Object> layoutVariables = new HashMap<>();
        layoutVariables.put("htmlContent", content);
        layoutVariables.put("background", component.getBackground() != null ? ColorUtils.normalizeCssColor(component.getBackground()) : "transparent");
        layoutVariables.put("borderWidth", component.getBorderWidth() != null ? component.getBorderWidth() : "0");
        layoutVariables.put("borderRadius", component.getBorderRadius() != null ? component.getBorderRadius() : "0");
        layoutVariables.put("borderColor", component.getBorderColor() != null ? ColorUtils.normalizeCssColor(component.getBorderColor()) : "transparent");
        layoutVariables.put("leftMargin", margins.getLeft());
        layoutVariables.put("rightMargin", margins.getRight());
        layoutVariables.put("topMargin", margins.getTop());
        layoutVariables.put("bottomMargin", margins.getBottom());
        layoutVariables.put("leftPadding", paddings.getLeft());
        layoutVariables.put("rightPadding", paddings.getRight());
        layoutVariables.put("topPadding", paddings.getTop());
        layoutVariables.put("bottomPadding", paddings.getBottom());
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/component-layout", layoutVariables);
    }

    protected abstract String renderContent(C component, ComponentData componentData);
}
