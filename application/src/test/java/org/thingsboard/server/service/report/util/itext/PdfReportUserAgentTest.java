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
package org.thingsboard.server.service.report.util.itext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xhtmlrenderer.pdf.ITextFSImage;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.resource.ImageResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Locks R2a security fix #2: {@link PdfReportUserAgent} must NOT fetch arbitrary URLs. Only three sources
 * resolve — {@code data:} base64, TB image URIs (via the injected resolver) and bundled classpath assets.
 * Anything with an http/https/file/ftp/... scheme is refused WITHOUT opening a connection (no SSRF, no
 * local-file read); the error image is substituted, exactly like any other unresolved image.
 */
class PdfReportUserAgentTest {

    private static final int WIDTH_PX = 500;

    private static PdfReportUserAgent newAgent(PdfReportImageResolver resolver) {
        ITextOutputDevice outputDevice = new ITextOutputDevice(26.666666f);
        return new PdfReportUserAgent(resolver, outputDevice, 20, WIDTH_PX);
    }

    private static PdfReportImageResolver failingResolver() {
        // If the resolver is touched for a non-tb-image URI, that's a bug — fail loudly.
        PdfReportImageResolver resolver = mock(PdfReportImageResolver.class);
        return resolver;
    }

    // --- The dangerous sink: resolveAndOpenStream must return null (never new URL().openStream()) ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void resolveAndOpenStream_refusesRemoteAndFileUris_noConnection() throws Exception {
        PdfReportImageResolver resolver = failingResolver();
        PdfReportUserAgent agent = newAgent(resolver);

        // Each of these would, in the vulnerable version, reach super.resolveAndOpenStream ->
        // new URL(uri).openStream(). After the fix the method short-circuits to null before any I/O.
        assertThat(agent.resolveAndOpenStream("http://169.254.169.254/latest/meta-data/")).isNull();
        assertThat(agent.resolveAndOpenStream("file:///etc/passwd")).isNull();
        assertThat(agent.resolveAndOpenStream("http://internal:8080/x")).isNull();
        assertThat(agent.resolveAndOpenStream("https://example.com/a.png")).isNull();
        assertThat(agent.resolveAndOpenStream("ftp://host/a.png")).isNull();

        verifyNoInteractions(resolver);
    }

    @Test
    void resolveAndOpenStream_stillResolvesBundledClasspathAsset() throws Exception {
        PdfReportUserAgent agent = newAgent(failingResolver());
        try (InputStream is = agent.resolveAndOpenStream("/html/styles/main.css")) {
            assertThat(is).as("bundled classpath asset must still resolve").isNotNull();
        }
    }

    // --- Behavioural: malicious image URIs are substituted with the error image ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void httpMetadataImageUri_substitutesErrorImage() {
        ImageResource resource = newAgent(failingResolver())
                .getImageResource("http://169.254.169.254/latest/meta-data/");
        assertThat(resource.getImage()).isInstanceOf(PdfSvgImage.class); // error image, no fetch
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void internalHostImageUri_substitutesErrorImage() {
        ImageResource resource = newAgent(failingResolver())
                .getImageResource("http://internal:8080/x");
        assertThat(resource.getImage()).isInstanceOf(PdfSvgImage.class);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void fileImageUri_isNotRead_substitutesErrorImage() throws Exception {
        // The file IS a valid PNG. The vulnerable version would read it and return a real ITextFSImage;
        // the fixed version never touches the filesystem, so an error image is substituted instead.
        File png = File.createTempFile("ssrf-probe", ".png");
        try {
            Files.write(png.toPath(), pngBytes());
            String fileUri = png.toURI().toString();
            ImageResource resource = newAgent(failingResolver()).getImageResource(fileUri);
            assertThat(resource.getImage())
                    .as("file:// image must be refused (error image), proving the file was not read")
                    .isInstanceOf(PdfSvgImage.class);
        } finally {
            Files.deleteIfExists(png.toPath());
        }
    }

    // --- The three legitimate sources still resolve ---

    @Test
    void dataUriResolvesToRealImage() {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes());
        ImageResource resource = newAgent(failingResolver()).getImageResource(dataUri);
        assertThat(resource.getImage()).isNotNull();
        assertThat(resource.getImage()).isNotInstanceOf(PdfSvgImage.class); // real image, not the error glyph
    }

    @Test
    void tbImageUriResolvesViaResolver() throws Exception {
        PdfReportImageResolver resolver = mock(PdfReportImageResolver.class);
        when(resolver.downloadImage(eq("tenant"), eq("mykey"))).thenReturn(pngBytes());

        ImageResource resource = newAgent(resolver).getImageResource("/api/images/tenant/mykey");

        assertThat(resource.getImage()).isNotNull();
        assertThat(resource.getImage()).isNotInstanceOf(PdfSvgImage.class);
        verify(resolver).downloadImage("tenant", "mykey");
    }

    /** A real (decodable) 8x8 PNG. */
    private static byte[] pngBytes() {
        try {
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    image.setRGB(x, y, 0x2b388f);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build test PNG", e);
        }
    }
}
