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
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.configuration.AlarmFilterConfig;
import org.thingsboard.server.common.data.report.configuration.CellSettings;
import org.thingsboard.server.common.data.report.configuration.ColumnSettings;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.DataSourceType;
import org.thingsboard.server.common.data.report.configuration.HeaderFooter;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.DividerComponent;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.ImageComponent;
import org.thingsboard.server.common.data.report.configuration.components.PageBreakComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.RichTextComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
import org.thingsboard.server.common.data.report.configuration.image.ImageSourceType;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.PageSize;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;
import org.thingsboard.server.common.data.report.configuration.timewindow.AggregationConfiguration;
import org.thingsboard.server.common.data.report.configuration.timewindow.FixedTimeWindow;
import org.thingsboard.server.common.data.report.configuration.timewindow.History;
import org.thingsboard.server.common.data.report.configuration.timewindow.Interval;
import org.thingsboard.server.common.data.report.configuration.timewindow.TimeWindowConfiguration;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.datasource.ReportDataService;
import org.thingsboard.server.service.report.renderer.AlarmTableRenderer;
import org.thingsboard.server.service.report.renderer.EntityTableRenderer;
import org.thingsboard.server.service.report.renderer.TimeseriesTableRenderer;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * Also covers the R2a security/correctness fixes: the DASHBOARD baseUrl override is ignored (#1), a bad
 * page-background color degrades gracefully (#9), and the still-guarded SUB_REPORT degrades to an error box.
 * <p>
 * R2b Task G extends it: the entity/alarm/time-series tables now render real rows (a mocked {@link
 * ReportDataService} feeds the inherited F2 builders), cell values are HTML-escaped (the injection defense),
 * and a table whose datasource can't be resolved degrades to an error box without failing the whole report.
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
    private final ReportDataService dataService = mock(ReportDataService.class);
    private final PdfReportService service = new PdfReportService(
            List.of(new org.thingsboard.server.service.report.renderer.HeadingRenderer(),
                    new org.thingsboard.server.service.report.renderer.RichTextRenderer(),
                    new org.thingsboard.server.service.report.renderer.DividerRenderer(),
                    new org.thingsboard.server.service.report.renderer.PageBreakRenderer(),
                    new org.thingsboard.server.service.report.renderer.ErrorRenderer(),
                    new org.thingsboard.server.service.report.renderer.ImageRenderer(),
                    new org.thingsboard.server.service.report.renderer.DashboardRenderer(),
                    new EntityTableRenderer(),
                    new AlarmTableRenderer(),
                    new TimeseriesTableRenderer()),
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
    void tableComponentWithNoDataSourceRendersErrorBoxNotFailure() throws Exception {
        // Tables are supported as of Task G, so an ENTITY_TABLE is no longer the generic "not supported" guard.
        // A table with no configured data source must still degrade to its OWN error box (not fail the report):
        // a HEADING + unconfigured ENTITY_TABLE renders a valid PDF (heading + "No data source" box), not a 500.
        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(heading("BODYHEADING"), new EntityTableComponent()));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
        assertThat(pdf.length).as("non-trivial PDF").isGreaterThan(1000);
        PdfReader reader = new PdfReader(pdf);
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).as("heading still rendered").contains("BODYHEADING");
            assertThat(text).as("table degraded to its own error box").contains("No data source is configured for ENTITY_TABLE");
            assertThat(text).as("no longer the R2a 'not supported' guard message").doesNotContain("not supported");
        } finally {
            reader.close();
        }
        verifyNoInteractions(dashboardReportService);
    }

    @Test
    void subReportRendersItsOwnErrorMessage() throws Exception {
        // SUB_REPORT gets its own message, not the generic table one.
        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(new org.thingsboard.server.common.data.report.configuration.components.SubReportComponent()));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1))
                    .contains("Sub-report components are not supported");
        } finally {
            reader.close();
        }
    }

    @Test
    void entityKeyImageWithNoBoundDataRendersGracefully() throws Exception {
        // R2b (F1): the entity-key IMAGE R2b guard is removed — ImageRenderer now resolves the URL from the
        // component's bound entity data. With no data bound (the server-side data layer is F2), the URL is
        // empty and AbstractImageRenderer substitutes the empty-image placeholder — a valid PDF with the
        // heading, NOT the old "not supported" error box and NOT a failure.
        PdfReportTemplateConfig config = baseConfig();
        ImageComponent entityKeyImage = new ImageComponent();
        entityKeyImage.setSourceType(ImageSourceType.ENTITY_KEY);
        config.setComponents(List.of(heading("BODYHEADING"), entityKeyImage));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(pdf);
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).as("heading still rendered").contains("BODYHEADING");
            assertThat(text).as("entity-key image no longer degrades to the R2b error box").doesNotContain("not supported");
        } finally {
            reader.close();
        }
        verifyNoInteractions(dashboardReportService);
    }

    @Test
    void dashboardComponentBaseUrlOverrideIsIgnored() {
        // R2a security fix #1: a component-supplied baseUrl must be ignored — the render always uses the
        // trusted server value (token-exfil / SSRF defense, mirroring DashboardReportController).
        when(dashboardReportService.generateReport(eq(tenantId), any(DashboardReportConfig.class)))
                .thenReturn(ReportData.builder().data(pngBytes()).name("d.png").contentType("image/png").build());

        PdfReportTemplateConfig config = baseConfig();
        DashboardReportConfig dashConfig = new DashboardReportConfig();
        dashConfig.setDashboardId("dash-1");
        dashConfig.setBaseUrl("http://attacker.example");
        DashboardComponent dashboard = new DashboardComponent();
        dashboard.setConfig(dashConfig);
        config.setComponents(List.of(dashboard));

        service.generateReport(task(), ctx(config));

        ArgumentCaptor<DashboardReportConfig> captor = ArgumentCaptor.forClass(DashboardReportConfig.class);
        verify(dashboardReportService).generateReport(eq(tenantId), captor.capture());
        assertThat(captor.getValue().getBaseUrl())
                .as("render must use the trusted server base URL, never the component-supplied one")
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void invalidPageBackgroundDegradesGracefully() {
        // R2a correctness fix #9: a bad page-background color must not fail the whole render.
        PdfReportTemplateConfig config = baseConfig();
        config.setPageBackground("definitely-not-a-color");
        config.setComponents(List.of(heading("BODYHEADING")));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
    }

    @Test
    void entityTableRendersColumnsAndRows() throws Exception {
        // Task G: an ENTITY_TABLE renders one row per entity the (mocked, permission-scoped) data layer returns.
        service.dataService = dataService;
        DeviceId devA = new DeviceId(UUID.randomUUID());
        DeviceId devB = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(devA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"), "label", tsv("Lab A"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-1")))),
                entity(devB, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device B"), "label", tsv("Lab B"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-2"))))));

        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(devA, List.of(dataKey("serialNumber", "attribute", "Serial")))));

        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(table));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
        String text = pdfText(pdf);
        assertThat(text).as("column header").contains("Serial");
        assertThat(text).as("both entity rows").contains("SN-1").contains("SN-2");
        verifyNoInteractions(dashboardReportService);
    }

    @Test
    void alarmTableRendersColumnsAndRows() throws Exception {
        // Task G: an ALARM_TABLE renders alarm rows. The entity ids the alarm read is scoped to come only from
        // the permission-scoped fetchEntities (F1 finding #1) — here the same mocked entity query feeds both.
        service.dataService = dataService;
        DeviceId devA = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(devA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))))));
        Alarm alarm = Alarm.builder().type("High Temperature").severity(AlarmSeverity.CRITICAL).build();
        when(dataService.findAlarmDataByQueryForEntities(any(), any(), any()))
                .thenReturn(page(new AlarmData(alarm, devA)));

        AlarmTableComponent table = new AlarmTableComponent();
        DataSource alarmSource = deviceDataSource(devA, List.of(dataKey("type", "alarm", "Type"), dataKey("severity", "alarm", "Severity")));
        alarmSource.setAlarmFilterConfig(new AlarmFilterConfig());
        table.setAlarmSource(alarmSource);
        table.setTimewindow(timeWindow(Aggregation.NONE));

        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(table));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        String text = pdfText(pdf);
        assertThat(text).as("alarm column headers").contains("Type").contains("Severity");
        assertThat(text).as("alarm row values").contains("High Temperature").contains("CRITICAL");
        verifyNoInteractions(dashboardReportService);
    }

    @Test
    void timeseriesTableRendersRowsAndHonorsTimewindow() throws Exception {
        // Task G: a TIME_SERIES_TABLE renders one row per timestamp, and the ts read uses the component's
        // (fixed) timewindow bounds + aggregation + limit — proving the configured window flows into the query.
        service.dataService = dataService;
        DeviceId devA = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(devA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))))));
        long startTs = 1_000_000L;
        long endTs = 2_000_000L;
        when(dataService.getTimeseries(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(tsEntry(1_500_000L, 21.5), tsEntry(1_600_000L, 22.5)));

        TimeseriesTableComponent table = new TimeseriesTableComponent();
        table.setDataSources(List.of(deviceDataSource(devA, List.of(dataKey("temperature", "timeseries", "Temp")))));
        table.setTimewindow(fixedWindow(startTs, endTs, Aggregation.NONE, 100));
        table.setShowTimestamp(true);
        table.setTimestampLabel("Time");
        table.setTimestampPattern("yyyy-MM-dd HH:mm:ss");

        PdfReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(table));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        String text = pdfText(pdf);
        assertThat(text).as("timestamp + series columns").contains("Time").contains("Temp");
        assertThat(text).as("one row per timestamp").contains("21.5").contains("22.5");
        // Timewindow honored: the read uses the component's fixed bounds, aggregation type and limit.
        verify(dataService).getTimeseries(any(), any(), eq(startTs), eq(endTs), any(), any(),
                eq(Aggregation.NONE), any(), eq(100), anyBoolean(), any());
        verifyNoInteractions(dashboardReportService);
    }

    @Test
    void entityTableCellValueIsHtmlEscaped() {
        // The table's marquee injection defense: a data-derived cell value carrying HTML/CSS metacharacters must
        // render as literal text (th:text), never as live markup. Drive the renderer directly to see the HTML.
        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(DataSource.builder()
                .type(DataSourceType.DEVICE).deviceId(UUID.randomUUID().toString())
                .dataKeys(List.of(dataKey("reading", "attribute", "Reading"))).build()));
        ComponentData componentData = new ComponentData(600);
        componentData.setEntityDatas(List.of(Map.of("Reading",
                "<script>alert(1)</script><img src=x onerror=alert(1)>")));

        String html = new EntityTableRenderer().render(table, componentData);

        assertThat(html)
                .as("cell value is HTML-escaped, not emitted as raw markup")
                .contains("&lt;script&gt;")
                .doesNotContain("<script>alert(1)</script>")
                .doesNotContain("<img src=x onerror");
    }

    @Test
    void entityTableCellFontFamilyIsAllowlisted() {
        // S1 fix (G review): the config font family is concatenated raw into a `font-family:${..}` style, and CSS
        // declarations are ';'-separated — so an un-allowlisted value must be collapsed to an allowlisted family
        // before it reaches the template, never injected as an extra declaration (regression of R2a's allowlist).
        Font maliciousFont = new Font();
        maliciousFont.setFamily("Roboto; background-image:url(http://attacker/x)");
        CellSettings cell = new CellSettings();
        cell.setFont(maliciousFont);
        ColumnSettings columnSettings = new ColumnSettings();
        columnSettings.setCell(cell);
        DataKey key = dataKey("reading", "attribute", "Reading");
        key.setSettings(columnSettings);

        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(DataSource.builder()
                .type(DataSourceType.DEVICE).deviceId(UUID.randomUUID().toString())
                .dataKeys(List.of(key)).build()));
        ComponentData componentData = new ComponentData(600);
        componentData.setEntityDatas(List.of(Map.of("Reading", "42")));

        String html = new EntityTableRenderer().render(table, componentData);

        assertThat(html)
                .as("injected CSS declaration is dropped; font-family collapses to the allowlisted default")
                .doesNotContain("background-image")
                .doesNotContain("url(http://attacker")
                .contains("font-family:Roboto");
    }

    @Test
    void tableWithUnresolvableSourceDegradesToErrorBox() throws Exception {
        // Graceful degradation (F2-review contract): a table whose datasource references a filter id not in the
        // template throws inside the data build — it must degrade to an error box for THAT component while the
        // rest of the report (the heading) still renders. buildComponentData stays inside renderComponent's try.
        service.dataService = dataService;
        EntityTableComponent table = new EntityTableComponent();
        DataSource ds = deviceDataSource(new DeviceId(UUID.randomUUID()), List.of(dataKey("serialNumber", "attribute", "Serial")));
        ds.setFilterId("filter-not-in-template");
        table.setDataSources(List.of(ds));

        PdfReportTemplateConfig config = baseConfig();
        config.setFilters(List.of()); // empty -> the referenced filter id is not found -> the build throws
        config.setComponents(List.of(heading("BODYHEADING"), table));

        byte[] pdf = service.generateReport(task(), ctx(config)).getData();

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).as("PDF magic header").isEqualTo("%PDF-");
        String text = pdfText(pdf);
        assertThat(text).as("other components still render").contains("BODYHEADING");
        assertThat(text).as("failing table degraded to an error box").contains("Failed to render component");
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

    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    @SafeVarargs
    private static EntityData entity(EntityId entityId, Map.Entry<EntityKeyType, Map<String, TsValue>>... latest) {
        Map<EntityKeyType, Map<String, TsValue>> latestMap = new HashMap<>();
        for (Map.Entry<EntityKeyType, Map<String, TsValue>> e : latest) {
            latestMap.put(e.getKey(), e.getValue());
        }
        return new EntityData(entityId, latestMap, new HashMap<>());
    }

    private static TsValue tsv(String value) {
        return new TsValue(0L, value);
    }

    private static TsKvEntry tsEntry(long ts, double value) {
        return new BasicTsKvEntry(ts, new DoubleDataEntry("temperature", value));
    }

    @SafeVarargs
    private static <T> PageData<T> page(T... items) {
        return new PageData<>(List.of(items), 1, items.length, false);
    }

    private static DataKey dataKey(String name, String type, String label) {
        DataKey dataKey = new DataKey();
        dataKey.setName(name);
        dataKey.setType(type);
        dataKey.setLabel(label);
        return dataKey;
    }

    private static DataSource deviceDataSource(DeviceId deviceId, List<DataKey> dataKeys) {
        return DataSource.builder()
                .type(DataSourceType.DEVICE)
                .deviceId(deviceId.getId().toString())
                .dataKeys(dataKeys)
                .build();
    }

    /** A "none" (history type 3 -> [0,0]) window; the reads are mocked so the range is irrelevant. */
    private static TimeWindowConfiguration timeWindow(Aggregation aggregation) {
        History history = new History();
        history.setHistoryType(3);
        history.setInterval(Interval.of(0L));
        AggregationConfiguration agg = new AggregationConfiguration();
        agg.setType(aggregation);
        agg.setLimit(100);
        TimeWindowConfiguration tw = new TimeWindowConfiguration();
        tw.setHistory(history);
        tw.setAggregation(agg);
        return tw;
    }

    /** A fixed (history type 1) window with deterministic bounds, so a query can be asserted against them. */
    private static TimeWindowConfiguration fixedWindow(long startTs, long endTs, Aggregation aggregation, int limit) {
        History history = new History();
        history.setHistoryType(1);
        FixedTimeWindow fixed = new FixedTimeWindow();
        fixed.setStartTimeMs(startTs);
        fixed.setEndTimeMs(endTs);
        history.setFixedTimewindow(fixed);
        history.setInterval(Interval.of(0L));
        AggregationConfiguration agg = new AggregationConfiguration();
        agg.setType(aggregation);
        agg.setLimit(limit);
        TimeWindowConfiguration tw = new TimeWindowConfiguration();
        tw.setHistory(history);
        tw.setAggregation(agg);
        return tw;
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
