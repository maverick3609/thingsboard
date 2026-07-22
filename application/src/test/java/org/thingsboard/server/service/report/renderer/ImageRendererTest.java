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

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.service.report.context.ComponentData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks R2a correctness fix #6: the inner content width is carried on the per-render {@link ComponentData}
 * rather than a field on the shared {@code @Component} singleton renderer, so two renders with different
 * widths cannot cross-contaminate. Exercised through {@link ImageRenderer} (the concrete {@link
 * AbstractImageRenderer} that reads the width back).
 */
class ImageRendererTest {

    private final ImageRenderer renderer = new ImageRenderer();

    private static ImageComponent image() {
        ImageComponent image = new ImageComponent();
        image.setSourceType(ImageSourceType.IMAGE);
        image.setImageUrl("data:image/png;base64,AAAA");
        return image; // no margins/paddings -> layoutWidthPx == usablePageWidthPx
    }

    @Test
    void layoutWidthComesFromComponentData() {
        ComponentData wide = new ComponentData(500);
        String wideHtml = renderer.render(image(), wide);
        // render() computes the inner width and stores it on the ComponentData it was handed.
        assertThat(wide.getLayoutWidthPx()).isEqualTo(500);
        assertThat(wideHtml).contains("500px");

        ComponentData narrow = new ComponentData(300);
        String narrowHtml = renderer.render(image(), narrow);
        assertThat(narrow.getLayoutWidthPx()).isEqualTo(300);
        assertThat(narrowHtml).contains("300px");

        // No cross-contamination between the two renders.
        assertThat(wideHtml).doesNotContain("300px");
        assertThat(narrowHtml).doesNotContain("500px");
    }
}
