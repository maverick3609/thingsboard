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
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.render.ReportRenderException;

/**
 * Renders an {@code IMAGE} component (PE {@code report.renderer.ImageRenderer}). For {@code sourceType
 * = image} the URL is the component's own {@code imageUrl} — a TB image URI ({@code /api/images/...},
 * resolved to bytes by the flying-saucer user agent via the ctx's {@code PdfReportImageResolver}), a data
 * URI, or a bundled asset fallback. {@link #getImageUrl} returns that URL; {@link AbstractImageRenderer}
 * emits the sized {@code <img>}.
 * <p>
 * {@code sourceType = entityKey} (the image URL comes from an entity's telemetry/attribute value) needs
 * the server-side data layer and is <b>R2b</b> — guarded here with a clear error.
 */
@Component
public class ImageRenderer extends AbstractImageRenderer<ImageComponent> {

    @Override
    protected String getImageUrl(ImageComponent component, ComponentData componentData) {
        if (ImageSourceType.ENTITY_KEY == component.getSourceType()) {
            // R2b: entity-key image sources resolve the URL from a DataSource/DataKey value (server-side
            // data layer). Until that lands, fail clearly rather than silently emitting an empty image.
            throw new ReportRenderException("entity-key image source is R2b");
        }
        String imageUrl = component.getImageUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = "/assets/report/components/image.svg";
        }
        return imageUrl;
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.IMAGE;
    }
}
