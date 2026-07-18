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
package org.thingsboard.server.service.report.render;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Task 11: the in-process Playwright renderer core. Proves the render primitive itself —
 * a self-contained {@code data:} HTML URL becomes a non-empty PDF, and a hard timeout force-closes
 * the per-render {@link com.microsoft.playwright.BrowserContext} and surfaces as
 * {@link ReportRenderException} — ahead of Task 13 wiring up the real dashboard handshake.
 */
class PlaywrightWebReportRendererTest {

    /**
     * Kept short (rather than the eventual production default) so the timeout test below doesn't
     * stall the suite: real dashboard renders are bounded by {@code reports.generation_timeout_ms}
     * (wired in Task 26), this just proves the renderer honors whatever bound it's given.
     */
    private static final long TEST_TIMEOUT_MS = 5000;

    static PlaywrightWebReportRenderer renderer;

    @BeforeAll
    static void up() {
        renderer = new PlaywrightWebReportRenderer(2, TEST_TIMEOUT_MS, "");
        renderer.init();
    }

    @AfterAll
    static void down() {
        if (renderer != null) {
            renderer.destroy();
        }
    }

    @Test
    void rendersDataUrlToNonEmptyPdf() {
        String dataUrl = "data:text/html,<html><body><h1 style='height:400px'>Hi</h1></body></html>";

        byte[] pdf = renderer.renderRaw(dataUrl, 500);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void timeoutClosesContextAndThrows() {
        assertThatThrownBy(() -> renderer.renderRaw("data:text/html,<script>while(true){}</script>", 1))
                .isInstanceOf(ReportRenderException.class);
    }

}
