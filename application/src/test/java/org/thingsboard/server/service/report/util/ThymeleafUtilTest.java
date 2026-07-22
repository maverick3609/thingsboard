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
package org.thingsboard.server.service.report.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Locks the data-only {@code ${...}} scanner that replaced Thymeleaf/OGNL evaluation of designer-authored
 * HEADING ({@link ThymeleafUtil#renderFromHtmlString}) and RICH_TEXT ({@link
 * ThymeleafUtil#renderFromTextString}) content — the latent SSTI sink (R2a security fix #3). User content is
 * never compiled as a template: expression payloads are inert, simple {@code ${name}} tokens substitute from
 * the variables map, HEADING escapes the substituted value, RICH_TEXT keeps its raw-HTML contract, and the
 * {@code pageNumber}/{@code totalPages} page-counter tokens still expand.
 */
class ThymeleafUtilTest {

    @Test
    void ssti_runtimeExecPayload_isNotEvaluated_heading() {
        Map<String, Object> vars = new HashMap<>();
        // The classic SSTI/RCE probe. It must NOT be evaluated: no OGNL, no T(...), no exec, no exception.
        assertThatCode(() -> {
            String out = ThymeleafUtil.renderFromHtmlString(
                    "${T(java.lang.Runtime).getRuntime().exec('id')}", vars);
            // Unknown token -> empty substitution. Never the process output of `id` (e.g. "uid=").
            assertThat(out).isEmpty();
            assertThat(out).doesNotContain("uid=").doesNotContain("java.lang.Runtime");
        }).doesNotThrowAnyException();
    }

    @Test
    void ssti_runtimeExecPayload_isNotEvaluated_richText() {
        Map<String, Object> vars = new HashMap<>();
        assertThatCode(() -> {
            String out = ThymeleafUtil.renderFromTextString(
                    "${T(java.lang.Runtime).getRuntime().exec('id')}", vars);
            assertThat(out).isEmpty();
            assertThat(out).doesNotContain("uid=");
        }).doesNotThrowAnyException();
    }

    @Test
    void simpleVariableSubstitution() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "World");
        assertThat(ThymeleafUtil.renderFromHtmlString("Hello ${name}", vars)).isEqualTo("Hello World");
        assertThat(ThymeleafUtil.renderFromTextString("Hello ${name}", vars)).isEqualTo("Hello World");
    }

    @Test
    void heading_htmlEscapesSubstitutedValue() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "<b>x</b>");
        String out = ThymeleafUtil.renderFromHtmlString("${name}", vars);
        // HEADING output is emitted via th:utext (raw) — so the value itself must arrive HTML-escaped.
        assertThat(out).isEqualTo("&lt;b&gt;x&lt;/b&gt;");
        assertThat(out).doesNotContain("<b>");
    }

    @Test
    void richText_keepsRawHtmlContract() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "<b>x</b>");
        // RICH_TEXT's existing contract is HTML — the substituted value stays raw (not escaped).
        assertThat(ThymeleafUtil.renderFromTextString("${name}", vars)).isEqualTo("<b>x</b>");
    }

    @Test
    void pageCounterTokensStillExpand() {
        Map<String, Object> empty = new HashMap<>();
        assertThat(ThymeleafUtil.renderFromHtmlString("${pageNumber}", empty))
                .isEqualTo("<span class=\"page-number\"></span>");
        assertThat(ThymeleafUtil.renderFromHtmlString("${totalPages}", empty))
                .isEqualTo("<span class=\"page-count\"></span>");
        assertThat(ThymeleafUtil.renderFromTextString("Page ${pageNumber} of ${totalPages}", empty))
                .isEqualTo("Page <span class=\"page-number\"></span> of <span class=\"page-count\"></span>");
    }

    @Test
    void nullValueRendersEmpty_noNpe() {
        assertThatCode(() -> {
            assertThat(ThymeleafUtil.renderFromHtmlString(null, new HashMap<>())).isEmpty();
            assertThat(ThymeleafUtil.renderFromTextString(null, new HashMap<>())).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void literalTextAndUnmatchedTokensAreInert() {
        Map<String, Object> empty = new HashMap<>();
        // Stray $, {, and an unclosed token are not variable tokens — left verbatim.
        assertThat(ThymeleafUtil.renderFromHtmlString("$5.00 for {item} ${unclosed", empty))
                .isEqualTo("$5.00 for {item} ${unclosed");
    }

    @Test
    void unknownVariableSubstitutesEmpty() {
        assertThat(ThymeleafUtil.renderFromHtmlString("a${missing}b", new HashMap<>())).isEqualTo("ab");
    }
}
