// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
