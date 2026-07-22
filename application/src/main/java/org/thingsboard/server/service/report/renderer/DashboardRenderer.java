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
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.service.report.context.ComponentData;

import java.util.Base64;

/**
 * Renders a {@code DASHBOARD} component (PE {@code report.renderer.DashboardRenderer}) as an embedded
 * image. The PNG is captured upstream by {@code PdfReportService} through R1's Playwright renderer (the
 * {@code DashboardReportService} PNG path) and handed in via {@link ComponentData#getImage()}; this
 * renderer base64-embeds it as a {@code data:image/png} {@code <img>}, sized/aligned by {@link
 * AbstractImageRenderer} exactly like any other image. This is how R1's dashboard-report path keeps
 * working through the new component engine.
 */
@Component
public class DashboardRenderer extends AbstractImageRenderer<DashboardComponent> {

    @Override
    protected String getImageUrl(DashboardComponent component, ComponentData componentData) {
        return encodeImage(componentData.getImage(), "image/png");
    }

    private String encodeImage(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        return "data:" + mimeType + ";base64," + base64;
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.DASHBOARD;
    }
}
