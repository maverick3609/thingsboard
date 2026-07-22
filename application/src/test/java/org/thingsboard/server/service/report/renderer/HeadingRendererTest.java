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
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.service.report.context.ComponentData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks two {@link HeadingRenderer} fixes: the null-{@code Font} NPE that turned a plain heading into an
 * error box (R2a correctness fix #5), and the {@code fontFamily} allowlist that stops a component's font
 * string from injecting arbitrary CSS into the template's {@code font-family:${fontFamily}} (R2a security
 * fix #4).
 */
class HeadingRendererTest {

    private static final int WIDTH_PX = 500;
    private final HeadingRenderer renderer = new HeadingRenderer();

    @Test
    void nullFontRendersHeadingTextNotErrorBox() {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Plain Heading");
        // font / weight / style / size / family all null — must not NPE, must not degrade to an error box.

        String html = renderer.render(heading, new ComponentData(WIDTH_PX));

        assertThat(html).contains("Plain Heading");
        assertThat(html).doesNotContain("Error!");
        // Defaults applied: Roboto family, normal weight/style.
        assertThat(html).contains("font-family: Roboto");
    }

    @Test
    void maliciousFontFamilyIsNeutralisedToDefault() {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Title");
        Font font = new Font();
        font.setFamily("x; background-image:url('http://a/leak')");
        heading.setFont(font);

        String html = renderer.render(heading, new ComponentData(WIDTH_PX));

        // Unknown family -> safe default; no injected declaration, no url() escape.
        assertThat(html).contains("font-family: Roboto");
        assertThat(html).doesNotContain("url(");
        assertThat(html).doesNotContain("background-image");
        assertThat(html).doesNotContain("leak");
    }

    @Test
    void allowlistedFontFamilyPassesThrough() {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue("Title");
        Font font = new Font();
        font.setFamily("serif");
        heading.setFont(font);

        String html = renderer.render(heading, new ComponentData(WIDTH_PX));

        assertThat(html).contains("font-family: serif");
    }
}
