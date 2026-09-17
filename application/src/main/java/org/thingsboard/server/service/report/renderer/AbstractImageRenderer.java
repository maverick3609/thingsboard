// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.report.configuration.components.AbstractImageComponent;
import org.thingsboard.server.common.data.report.configuration.image.ImageAlignment;
import org.thingsboard.server.common.data.report.configuration.image.ImageWidthType;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.util.ImageUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.Map;

/**
 * Base for the two image-emitting renderers (PE {@code report.renderer.AbstractImageRenderer}): the
 * {@code IMAGE} component ({@link ImageRenderer}) and the {@code DASHBOARD} component ({@link
 * DashboardRenderer}, whose "image" is the Playwright PNG). Resolves the source URL ({@link #getImageUrl})
 * then emits {@code html/components/image} sized per the component's {@link ImageWidthType} (fit-width /
 * original / custom-px) and {@link ImageAlignment}.
 *
 * @param <C> the concrete image-bearing component type
 */
public abstract class AbstractImageRenderer<C extends AbstractImageComponent>
        extends ReportComponentWithLayoutRenderer<C> {

    @Override
    public String renderContent(C component, ComponentData componentData) {
        String imageUrl = getImageUrl(component, componentData);
        String layoutWidth = componentData.getLayoutWidthPx() + "px";
        Object imageWidth;
        if (ImageWidthType.ORIGINAL == component.getWidthType()) {
            imageWidth = "auto";
        } else if (ImageWidthType.CUSTOM == component.getWidthType()) {
            int customWidth = component.getCustomWidth() >= 1 ? component.getCustomWidth() : 100;
            imageWidth = customWidth + "px";
        } else {
            imageWidth = layoutWidth;
        }
        String imageAlign = component.getAlignment() != null ? component.getAlignment().getValue() : ImageAlignment.CENTER.getValue();
        Map<String, Object> componentVariables = Map.of(
                "layoutWidth", layoutWidth,
                "imageUrl", StringUtils.isBlank(imageUrl) ? ImageUtils.EMPTY_IMAGE_URI : imageUrl,
                "imageWidth", imageWidth,
                "imageAlign", imageAlign);
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/image", componentVariables);
    }

    protected abstract String getImageUrl(C component, ComponentData componentData);
}
