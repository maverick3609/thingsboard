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
package org.thingsboard.server.service.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.configuration.HeaderFooter;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.DividerComponent;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.components.PageBreakComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.RichTextComponent;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.render.ReportRenderException;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for the R2a HTML→PDF engine ({@link PdfReportService}) — no browser, no Spring. Drives a
 * {@link PdfReportTemplateConfig} carrying every non-data component (HEADING + RICH_TEXT + DIVIDER +
 * PAGE_BREAK + IMAGE + DASHBOARD) plus a running header through the real component renderers and the
 * C4 flying-saucer/openpdf plumbing, with the DASHBOARD's Playwright PNG mocked. Asserts a valid
 * multi-page PDF, the dashboard captured as PNG (not PDF) and embedded, and the header on every page.
 * A table component asserts the R2b guard.
 */
class PdfReportServiceTest {

    /** No tb-image URIs in this test — fail loudly if the resolver is hit. */
    private static final PdfReportImageResolver STUB_RESOLVER = new PdfReportImageResolver() {
        @Override
        public byte[] downloadImage(String type, String key) {
            throw new UnsupportedOperationException("no tb-image expected: " + type + "/" + key);
        }

        @Override
        public byte[] downloadPublicImage(String key) {
            throw new UnsupportedOperationException("no public tb-image expected: " + key);
        }
    };

    private static final String HEADER_MARKER = "HEADERMARK";

    private final DashboardReportService dashboardReportService = mock(DashboardReportService.class);
    private final PdfReportService service = new PdfReportService(
            List.of(new org.thingsboard.server.service.report.renderer.HeadingRenderer(),
                    new org.thingsboard.server.service.report.renderer.RichTextRenderer(),
                    new org.thingsboard.server.service.report.renderer.DividerRenderer(),
                    new org.thingsboard.server.service.report.renderer.PageBreakRenderer(),
                    new org.thingsboard.server.service.report.renderer.ErrorRenderer(),
                    new org.thingsboard.server.service.report.renderer.ImageRenderer(),
                    new org.thingsboard.server.service.report.renderer.DashboardRenderer()),
            dashboardReportService,
            "http://localhost:8080");

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());
    private final ReportTemplateId templateId = new ReportTemplateId(UUID.randomUUID());

    @Test
    void rendersMultiPagePdfWithEmbeddedDashboardPngAndRunningHeader() throws Exception {
        byte[] dashboardPng = pngBytes();
        when(dashboardReportService.generateReport(eq(tenantId), any(DashboardReportConfig.class)))
                .thenReturn(ReportData.builder().data(dashboardPng).name("d.png").contentType("image/png").build());

        PdfReportTemplateConfig config = baseConfig();
        config.setHeader(runningHeader());
        config.setComponents(List.of(
                heading("BODYHEADING"),
                richText("<p>Rich text body paragraph.</p>"),
                new DividerComponent(),
                new PageBreakComponent(),
                imageComponent(),
                dashboardComponent()));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();
        System.out.println("[PdfReportServiceTest] produced PDF byte size = " + pdf.length);

        // Valid PDF.
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
        assertThat(pdf.length).as("non-trivial PDF").isGreaterThan(3000);

        // DASHBOARD captured as PNG (not PDF) via R1's Playwright path, then embedded.
        ArgumentCaptor<DashboardReportConfig> captor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(eq(tenantId), captor.capture());
        assertThat(captor.getValue().getType()).as("dashboard requested as PNG").isEqualTo("png");
        assertThat(captor.getValue().getDashboardId()).isEqualTo("dash-1");

        PdfReader reader = new PdfReader(pdf);
        try {
            // PAGE_BREAK forces at least a second page.
            assertThat(reader.getNumberOfPages()).as("multi-page from PAGE_BREAK").isGreaterThanOrEqualTo(2);
            // Running header renders on EVERY page.
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                assertThat(extractor.getTextFromPage(page))
                        .as("running header present on page %d", page)
                        .contains(HEADER_MARKER);
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void tableComponentRaisesR2bGuard() {
        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(new EntityTableComponent()));

        assertThatThrownBy(() -> service.generateReport(task(), ctx(config)))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("table components are R2b");

        verifyNoInteractions(dashboardReportService);
    }

    // --- fixtures ---

    private TbReportCtx ctx(PdfReportTemplateConfig config) {
        return TbReportCtx.builder()
                .tenantId(tenantId)
                .configuration(config)
                .timeZone("UTC")
                .userId(userId)
                .accessToken("jwt")
                .imageResolver(STUB_RESOLVER)
                .build();
    }

    private ReportTask task() {
        return ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(templateId).userId(userId).timezone("UTC").build();
    }

    private PdfReportTemplateConfig baseConfig() {
        PdfReportTemplateConfig config = new PdfReportTemplateConfig();
        config.setPageSize(PageSize.A4);
        config.setNamePattern("report-%d{yyyy-MM-dd}");
        return config;
    }

    private HeaderFooter runningHeader() {
        HeaderFooter header = new HeaderFooter();
        header.setEnabled(true);
        header.setComponents(List.of(heading(HEADER_MARKER)));
        return header;
    }

    private HeadingComponent heading(String value) {
        HeadingComponent heading = new HeadingComponent();
        heading.setValue(value);
        heading.setFont(font());
        heading.setColor("#000000");
        heading.setTextAlignment(TextAlignment.CENTER);
        heading.setVerticalAlignment(VerticalAlignment.MIDDLE);
        heading.setHeight(24);
        return heading;
    }

    private RichTextComponent richText(String value) {
        RichTextComponent richText = new RichTextComponent();
        richText.setValue(value);
        return richText;
    }

    private ImageComponent imageComponent() {
        ImageComponent image = new ImageComponent();
        image.setSourceType(ImageSourceType.IMAGE);
        image.setImageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes()));
        return image;
    }

    private DashboardComponent dashboardComponent() {
        DashboardReportConfig dashConfig = new DashboardReportConfig();
        dashConfig.setDashboardId("dash-1");
        DashboardComponent dashboard = new DashboardComponent();
        dashboard.setConfig(dashConfig);
        return dashboard;
    }

    private static Font font() {
        Font font = new Font();
        font.setSize(12f);
        font.setWeight(FontWeight.NORMAL);
        font.setStyle(FontStyle.NORMAL);
        font.setFamily("Roboto");
        return font;
    }

    /** A real (decodable) 8x8 PNG so flying-saucer embeds it rather than substituting an error image. */
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
