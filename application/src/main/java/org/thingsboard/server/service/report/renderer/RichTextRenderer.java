// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
