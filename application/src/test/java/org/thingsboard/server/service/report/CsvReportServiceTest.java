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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.CsvReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.DataSourceType;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.HeadingComponent;
import org.thingsboard.server.common.data.report.configuration.components.SubReportComponent;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.context.TbReportCtxProvider;
import org.thingsboard.server.service.report.datasource.ReportDataService;
import org.thingsboard.server.service.report.renderer.CsvAlarmTableRenderer;
import org.thingsboard.server.service.report.renderer.CsvEntityTableRenderer;
import org.thingsboard.server.service.report.renderer.CsvTimeseriesTableRenderer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the R2b CSV engine ({@link CsvReportService}) — no Spring, {@link ReportDataService} mocked so
 * the render logic is under test, not the DB. Covers: an ENTITY_TABLE rendered to header + data rows; the
 * marquee CSV formula-injection guard on real report data; non-tabular components silently excluded; the
 * sub-report recursion bounded by the SAME depth + budget as the PDF path (the shared {@link TbReportCtx}
 * constants); a non-CSV child template degrading to an error row; and {@link TbReportService} routing a CSV
 * template to this engine (the R2a "No render service for CSV" reject is gone).
 */
class CsvReportServiceTest {

    private final ReportDataService dataService = mock(ReportDataService.class);
    private final CsvReportService service = new CsvReportService(
            List.of(new CsvEntityTableRenderer(), new CsvAlarmTableRenderer(), new CsvTimeseriesTableRenderer()));

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final UserId userId = new UserId(UUID.randomUUID());
    private final ReportTemplateId templateId = new ReportTemplateId(UUID.randomUUID());

    @Test
    void entityTableRendersHeaderAndRowsToCsv() {
        // An ENTITY_TABLE renders one row per permission-scoped entity the mocked data layer returns, columns
        // from the source's data keys.
        service.dataService = dataService;
        DeviceId devA = new DeviceId(UUID.randomUUID());
        DeviceId devB = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(devA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-1")))),
                entity(devB, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device B"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-2"))))));

        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(devA, List.of(dataKey("serialNumber", "attribute", "Serial")))));
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(table));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8);

        List<String> lines = csv.lines().toList();
        assertThat(lines).as("header + one row per entity").hasSize(3);
        assertThat(lines.get(0)).as("column header row").isEqualTo("Serial");
        assertThat(lines).anyMatch(l -> l.equals("SN-1")).anyMatch(l -> l.equals("SN-2"));
    }

    @Test
    void csvContentTypeAndNameAreSet() {
        service.dataService = dataService;
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page());
        CsvReportTemplateConfig config = baseConfig();
        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(new DeviceId(UUID.randomUUID()),
                List.of(dataKey("serialNumber", "attribute", "Serial")))));
        config.setComponents(List.of(table));

        var reportData = service.generateReport(task(), ctx(config));

        assertThat(reportData.getContentType()).isEqualTo("text/csv");
        assertThat(reportData.getName()).startsWith("report-");
    }

    @Test
    void entityTableCellFormulaValuesAreNeutralizedInCsv() {
        // THE marquee security deliverable: attacker-influenceable data values that begin with a spreadsheet
        // formula trigger are neutralised (leading single quote) in the generated CSV, so opening the file in
        // Excel / Sheets / LibreOffice cannot execute a formula. Asserted on the raw CSV bytes.
        service.dataService = dataService;
        DeviceId dev = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(dev,
                        Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device X"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of(
                                "f1", tsv("=1+1"),
                                "f2", tsv("+1"),
                                "f3", tsv("-1"),
                                "f4", tsv("@SUM(A1)"),
                                "f5", tsv("\t=cmd"),
                                "f6", tsv("\r=cmd"))))));

        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(dev, List.of(
                dataKey("f1", "attribute", "C1"), dataKey("f2", "attribute", "C2"),
                dataKey("f3", "attribute", "C3"), dataKey("f4", "attribute", "C4"),
                dataKey("f5", "attribute", "C5"), dataKey("f6", "attribute", "C6")))));
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(table));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8);

        // Each dangerous value is present only in its neutralised form.
        assertThat(csv).contains("'=1+1").contains("'+1").contains("'-1").contains("'@SUM(A1)");
        // No field opens as a live formula: no trigger immediately after a record start or a delimiter.
        assertThat(csv.stripLeading())
                .doesNotStartWith("=").doesNotStartWith("+").doesNotStartWith("-").doesNotStartWith("@");
        assertThat(csv).doesNotContain(",=").doesNotContain(",+").doesNotContain(",-").doesNotContain(",@");
    }

    @Test
    void nonTableComponentsAreExcludedFromCsv() {
        // A HEADING has no CSV representation and is silently excluded; only the ENTITY_TABLE's rows appear.
        service.dataService = dataService;
        DeviceId dev = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(dev, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-1"))))));

        HeadingComponent heading = new HeadingComponent();
        heading.setValue("MY HEADING");
        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(dev, List.of(dataKey("serialNumber", "attribute", "Serial")))));
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(heading, table));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8);

        assertThat(csv).as("heading excluded from CSV").doesNotContain("MY HEADING");
        assertThat(csv).as("only the table's rows").contains("Serial").contains("SN-1");
        assertThat(csv.lines().toList()).as("header + one row, no heading row").hasSize(2);
    }

    @Test
    void csvSubReportSelfReferenceIsBoundedByMaxDepth() throws Exception {
        // Security deliverable #2 (depth): a CSV template whose SUB_REPORT points at itself must NOT recurse
        // into a StackOverflowError. The SHARED TbReportCtx.MAX_SUB_REPORT_DEPTH cap stops it — findReportTemplate
        // is invoked exactly the cap number of times (proving the recursion actually stopped), and the report
        // completes with a depth-limit error row. Mirrors PdfReportServiceTest.subReportSelfReferenceIsBoundedByMaxDepth.
        service.dataService = dataService;
        ReportTemplate cyclicTemplate = new ReportTemplate();
        CsvReportTemplateConfig cyclicConfig = baseConfig();
        SubReportComponent selfRef = new SubReportComponent();
        selfRef.setTemplateId(templateId); // no data source -> [null] entity -> one recursion per level
        cyclicConfig.setComponents(List.of(selfRef));
        cyclicTemplate.setConfiguration(cyclicConfig);
        when(dataService.findReportTemplate(any(), any())).thenReturn(cyclicTemplate);

        SubReportComponent parentSub = new SubReportComponent();
        parentSub.setTemplateId(templateId);
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(parentSub));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8); // must not SOE

        verify(dataService, times(TbReportCtx.MAX_SUB_REPORT_DEPTH)).findReportTemplate(any(), any());
        assertThat(csv).contains("exceeds the maximum depth");
    }

    @Test
    void csvSubReportFanOutIsBoundedByTotalRenderBudget() throws Exception {
        // Security deliverable #2 (fan-out): a self-reference over E=2 entities is an E-ary tree (E^depth renders)
        // that would exhaust the shared render heap even within the depth cap. The SHARED per-report budget caps
        // TOTAL renders across every branch, so findReportTemplate never exceeds MAX_TOTAL_SUB_REPORT_RENDERS.
        // Mirrors PdfReportServiceTest.subReportFanOutIsBoundedByTotalRenderBudget.
        service.dataService = dataService;
        DeviceId devA = new DeviceId(UUID.randomUUID());
        DeviceId devB = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(devA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A")))),
                entity(devB, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device B")))))); // E = 2

        ReportTemplate cyclicTemplate = new ReportTemplate();
        CsvReportTemplateConfig cyclicConfig = baseConfig();
        SubReportComponent selfRef = new SubReportComponent();
        selfRef.setTemplateId(templateId);
        selfRef.setDataSources(List.of(deviceDataSource(devA, List.of(dataKey("name", "entityField", "Name"))))); // fans out
        cyclicConfig.setComponents(List.of(selfRef));
        cyclicTemplate.setConfiguration(cyclicConfig);
        when(dataService.findReportTemplate(any(), any())).thenReturn(cyclicTemplate);

        SubReportComponent parentSub = new SubReportComponent();
        parentSub.setTemplateId(templateId);
        parentSub.setDataSources(List.of(deviceDataSource(devA, List.of(dataKey("name", "entityField", "Name")))));
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(parentSub));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8); // must not OOM/SOE

        verify(dataService, atMost(TbReportCtx.MAX_TOTAL_SUB_REPORT_RENDERS)).findReportTemplate(any(), any());
        assertThat(csv).contains("render budget");
    }

    @Test
    void csvSubReportWithNonCsvChildRendersErrorRow() throws Exception {
        // A CSV sub-report referencing a non-CSV (PDF) template degrades to an error row via the instanceof
        // guard — no ClassCastException, no force-rendering a PDF template as CSV. Mirrors the PDF path's
        // symmetric "is not a PDF report template" guard.
        service.dataService = dataService;
        ReportTemplate pdfChild = new ReportTemplate();
        pdfChild.setConfiguration(new PdfReportTemplateConfig());
        when(dataService.findReportTemplate(eq(templateId), any())).thenReturn(pdfChild);

        SubReportComponent subReport = new SubReportComponent();
        subReport.setTemplateId(templateId);
        CsvReportTemplateConfig config = baseConfig();
        config.setComponents(List.of(subReport));

        String csv = new String(service.generateReport(task(), ctx(config)).getData(), StandardCharsets.UTF_8);

        assertThat(csv).contains("is not a CSV report template");
    }

    @Test
    void tbReportServiceRoutesCsvTemplateToCsvEngine() {
        // End-to-end dispatch: TbReportService picks the CsvReportService for a TbReportFormat.CSV template and
        // persists text/csv bytes (the R2a "No render service registered for CSV" reject is gone once CSV
        // registers). The CSV engine itself is exercised above; this is the routing contract.
        service.dataService = dataService;
        DeviceId dev = new DeviceId(UUID.randomUUID());
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(
                entity(dev, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))),
                        Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-1"))))));

        CsvReportTemplateConfig config = baseConfig();
        EntityTableComponent table = new EntityTableComponent();
        table.setDataSources(List.of(deviceDataSource(dev, List.of(dataKey("serialNumber", "attribute", "Serial")))));
        config.setComponents(List.of(table));

        ReportTemplate template = new ReportTemplate(templateId);
        template.setTenantId(tenantId);
        template.setName("CSV Report");
        template.setFormat(TbReportFormat.CSV);
        template.setType(ReportTemplateType.REPORT);
        template.setConfiguration(config);

        var reportDao = mock(org.thingsboard.server.dao.report.ReportService.class);
        var templateDao = mock(ReportTemplateService.class);
        var ctxProvider = mock(TbReportCtxProvider.class);
        ReportTask task = task();
        when(templateDao.findReportTemplateById(tenantId, templateId)).thenReturn(template);
        when(ctxProvider.newContext(task)).thenReturn(ctx(config));
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        when(reportDao.createReport(any(Report.class), bytesCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        TbReportService dispatcher = new TbReportService(reportDao, templateDao, ctxProvider, List.of(service));
        dispatcher.generateReport(task);

        verify(reportDao).createReport(any(Report.class), any(byte[].class));
        String csv = new String(bytesCaptor.getValue(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Serial").contains("SN-1");
    }

    // --- fixtures ---

    private TbReportCtx ctx(CsvReportTemplateConfig config) {
        return TbReportCtx.builder()
                .tenantId(tenantId)
                .configuration(config)
                .timeZone("UTC")
                .userId(userId)
                .reportCreatedTime("2026-07-23T00:00:00Z")
                .build();
    }

    private ReportTask task() {
        return ReportTask.builder()
                .tenantId(tenantId).reportTemplateId(templateId).userId(userId).timezone("UTC").build();
    }

    private CsvReportTemplateConfig baseConfig() {
        CsvReportTemplateConfig config = new CsvReportTemplateConfig();
        config.setNamePattern("report-%d{yyyy-MM-dd}");
        config.setEntityAliases(List.of());
        config.setFilters(List.of());
        return config;
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

}
