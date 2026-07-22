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
        String layoutWidth = this.layoutWidthPx + "px";
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
