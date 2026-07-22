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
import org.thingsboard.server.service.report.util.itext.PdfReportFontResolver;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;
import org.thingsboard.server.service.report.util.itext.PdfSvgDocument;
import org.w3c.dom.Document;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De-risking proof for Task C4: drives the ported Thymeleaf + flying-saucer + openpdf + jsvg + fonts
 * stack end-to-end and asserts a real PDF is produced, an embedded font is resolvable off the
 * classpath, and an SVG rasterizes via jsvg — without throwing on font resolution or SVG raster.
 */
class HtmlToPdfSmokeTest {

    /** Never invoked in this test (no tb-image URIs) — fails loudly if the image seam is hit. */
    private static final PdfReportImageResolver STUB_RESOLVER = new PdfReportImageResolver() {
        @Override
        public byte[] downloadImage(String type, String key) {
            throw new UnsupportedOperationException("no tb-image expected in smoke test: " + type + "/" + key);
        }

        @Override
        public byte[] downloadPublicImage(String key) {
            throw new UnsupportedOperationException("no public tb-image expected in smoke test: " + key);
        }
    };

    private static final String RAW_SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"60\" height=\"60\">"
                    + "<rect width=\"60\" height=\"60\" fill=\"#2b388f\"/>"
                    + "<circle cx=\"30\" cy=\"30\" r=\"20\" fill=\"#ec1c24\"/></svg>";

    @Test
    void rendersHtmlWithEmbeddedFontAndInlineSvgToRealPdf() {
        String svgDataUri = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(RAW_SVG.getBytes(StandardCharsets.UTF_8));
        String html = "<!DOCTYPE html><html><head><style>"
                + "@page { size: A4; margin: 20px; }"
                + "body { font-family: 'Roboto'; font-size: 14px; }"
                + "h1 { font-weight: bold; }"
                + "</style></head><body>"
                + "<h1>Inferrix Report Engine Smoke Test</h1>"
                + "<p>Rendered with embedded Roboto via flying-saucer + openpdf.</p>"
                + "<img src=\"" + svgDataUri + "\"/>"
                + "</body></html>";

        int usablePageWidthPx = 555;
        ITextRenderer renderer = HtmlRenderUtils.createRenderer(STUB_RESOLVER, usablePageWidthPx);
        Document document = HtmlRenderUtils.parseDom(html);
        renderer.setDocument(document);
        renderer.layout();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.createPDF(out);
        byte[] pdf = out.toByteArray();

        System.out.println("[HtmlToPdfSmokeTest] produced PDF byte size = " + pdf.length);
        assertThat(pdf).as("PDF bytes produced").isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
                .as("PDF magic header").isEqualTo("%PDF-");
        assertThat(pdf.length)
                .as("non-trivial PDF (embedded Roboto subset makes it several KB)")
                .isGreaterThan(3000);
    }

    @Test
    void thymeleafMeasureTemplateAndCssResolveOffClasspath() throws Exception {
        // Drives ThymeleafUtil.renderFromHtmlTemplate against a real classpath template, then a
        // throwaway ITextRenderer whose user agent resolves /html/styles/main.css off the classpath.
        ITextRenderer renderer = HtmlRenderUtils.createRenderer(STUB_RESOLVER, 555);
        String fragment = "<div style=\"font-family:'Roboto'\">Measured fragment line one.<br/>line two.</div>";
        int height = HtmlRenderUtils.measureHtmlHeight(renderer, fragment, 555);
        assertThat(height).as("measured fragment height (px)").isPositive();
    }

    @Test
    void thymeleafRendersFromClasspathHtmlTemplate() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("htmlContent", "<span id=\"marker\">hello</span>");
        variables.put("pageWidth", "555px");
        variables.put("pageHeight", "1000px");
        String rendered = ThymeleafUtil.renderFromHtmlTemplate("html/measure-template", variables);
        assertThat(rendered).contains("hello").contains("555px").contains("1000px");
    }

    @Test
    void jsvgRasterizesSvgToPng() throws Exception {
        PdfSvgDocument svg = PdfSvgDocument.fromSvgString(RAW_SVG);
        assertThat(svg).as("jsvg parsed the SVG").isNotNull();
        byte[] png = svg.render(60.0f, 60.0f, 120);
        assertThat(png).as("rasterized PNG bytes").isNotEmpty();
        // PNG magic: 89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    void fontResolverFindsRobotoTtfOnClasspath() {
        assertThat(PdfReportFontResolver.class.getResource("/fonts/roboto/Roboto-Regular.ttf"))
                .as("Roboto-Regular.ttf loadable off the classpath by the font resolver")
                .isNotNull();
    }
}
