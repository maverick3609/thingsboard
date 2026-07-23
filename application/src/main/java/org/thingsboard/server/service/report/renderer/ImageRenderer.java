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
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.service.report.context.ComponentData;

import java.util.List;
import java.util.Map;

/**
 * Renders an {@code IMAGE} component (PE {@code report.renderer.ImageRenderer}). For {@code sourceType
 * = image} the URL is the component's own {@code imageUrl} — a TB image URI ({@code /api/images/...},
 * resolved to bytes by the flying-saucer user agent via the ctx's {@code PdfReportImageResolver}), a data
 * URI, or a bundled asset fallback.
 * <p>
 * For {@code sourceType = entityKey} (R2b) the URL comes from an entity's telemetry/attribute <b>value</b>:
 * the server-side data layer resolves the component's {@code DataSource}/{@code DataKey} into {@code
 * componentData.entityDatas} (permission-scoped, under the report task's {@code SecurityUser}), and this
 * reads the first row's value for the first data key — itself a TB image URI, downloaded by the user agent
 * through {@code ReportDataService.downloadImage(ctx, …)}. Until a row is bound the URL is empty and {@link
 * AbstractImageRenderer} substitutes the empty-image placeholder (graceful, no failure).
 */
@Component
public class ImageRenderer extends AbstractImageRenderer<ImageComponent> {

    @Override
    protected String getImageUrl(ImageComponent component, ComponentData componentData) {
        if (ImageSourceType.ENTITY_KEY == component.getSourceType()) {
            List<Map<String, String>> entityDatas = componentData.getEntityDatas();
            if (entityDatas != null && !entityDatas.isEmpty()) {
                DataKey dataKey = firstDataKey(component);
                if (dataKey != null) {
                    return entityDatas.get(0).get(dataKey.getLabel());
                }
            }
            return "";
        }
        String imageUrl = component.getImageUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            imageUrl = "/assets/report/components/image.svg";
        }
        return imageUrl;
    }

    /** The component's first data key (the entity-key image binds to its {@code label}), or {@code null} if unconfigured. */
    private static DataKey firstDataKey(ImageComponent component) {
        List<DataSource> dataSources = component.getDataSources();
        if (dataSources == null || dataSources.isEmpty() || dataSources.get(0) == null) {
            return null;
        }
        List<DataKey> dataKeys = dataSources.get(0).getDataKeys();
        if (dataKeys == null || dataKeys.isEmpty()) {
            return null;
        }
        return dataKeys.get(0);
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.IMAGE;
    }
}
