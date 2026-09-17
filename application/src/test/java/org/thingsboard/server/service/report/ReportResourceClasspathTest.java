// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the classpath locations of the R2a report engine resources (Task C2).
 * <p>
 * The PE-ported engine resolves these via absolute classpath paths, so the
 * files MUST live at the resource root (not under {@code /report/}):
 * <ul>
 *   <li>Thymeleaf templates — {@code ThymeleafUtil}'s {@code ClassLoaderTemplateResolver}
 *       uses prefix {@code "/"} + name {@code "html/..."} + suffix {@code ".html"},
 *       i.e. {@code /html/report-template.html}.</li>
 *   <li>Fonts — {@code PdfReportFontResolver} loads {@code /fonts/roboto/Roboto-Regular.ttf} etc.
 *       via {@code getResource(uri)} with leading-slash absolute paths.</li>
 *   <li>CSS — templates link {@code /html/styles/main.css}.</li>
 *   <li>SVG — {@code PdfReportUserAgent} renders {@code /svg/error-image.svg}.</li>
 * </ul>
 * If this test breaks, the engine will fail to resolve templates/fonts at render time.
 */
class ReportResourceClasspathTest {

    private byte[] readClasspath(String absolutePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(absolutePath)) {
            assertThat(is)
                    .as("classpath resource %s must be present", absolutePath)
                    .isNotNull();
            return is.readAllBytes();
        }
    }

    @Test
    void reportTemplateResolvesOnClasspath() throws IOException {
        byte[] html = readClasspath("/html/report-template.html");
        assertThat(html).isNotEmpty();
        assertThat(new String(html)).containsIgnoringCase("html");
    }

    @Test
    void componentAndMeasureTemplatesResolveOnClasspath() throws IOException {
        assertThat(readClasspath("/html/measure-template.html")).isNotEmpty();
        assertThat(readClasspath("/html/components/heading-template.html")).isNotEmpty();
        assertThat(readClasspath("/html/components/error-template.html")).isNotEmpty();
        assertThat(readClasspath("/html/styles/main.css")).isNotEmpty();
        assertThat(readClasspath("/svg/error-image.svg")).isNotEmpty();
    }

    @Test
    void embeddedFontResolvesOnClasspath() throws IOException {
        byte[] ttf = readClasspath("/fonts/roboto/Roboto-Regular.ttf");
        // A TrueType font starts with the sfnt version tag 0x00010000.
        assertThat(ttf.length).isGreaterThan(1000);
        assertThat(ttf[0]).isEqualTo((byte) 0x00);
        assertThat(ttf[1]).isEqualTo((byte) 0x01);
        assertThat(ttf[2]).isEqualTo((byte) 0x00);
        assertThat(ttf[3]).isEqualTo((byte) 0x00);
    }
}
