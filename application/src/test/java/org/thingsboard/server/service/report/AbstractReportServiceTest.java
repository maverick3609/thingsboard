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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thingsboard.script.api.tbel.DefaultTbelInvokeService;
import org.thingsboard.script.api.tbel.TbelInvokeService;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.AlarmFilterConfig;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.DataSourceType;
import org.thingsboard.server.common.data.report.configuration.PdfReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
import org.thingsboard.server.common.data.report.configuration.timewindow.AggregationConfiguration;
import org.thingsboard.server.common.data.report.configuration.timewindow.History;
import org.thingsboard.server.common.data.report.configuration.timewindow.Interval;
import org.thingsboard.server.common.data.report.configuration.timewindow.TimeWindowConfiguration;
import org.thingsboard.server.common.stats.DefaultStatsFactory;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.datasource.ReportDataService;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for the shared report data layer ({@link AbstractReportService}) — the entity/timeseries/alarm
 * {@link ComponentData} builds and the TBEL post-processing. The {@link ReportDataService} is mocked (the
 * build logic is under test, not the DB), so entity/alarm/timeseries rows are asserted against fixed fixture
 * data. A real {@link TbelInvokeService} is wired through a minimal Spring context ({@code
 * DefaultTbelInvokeService} + its two collaborators — no DB, no Colima) exactly as {@code
 * AbstractTbelInvokeTest} does, so the post-processing test proves a real sandboxed script transforms a value.
 * <p>
 * The alarm test also proves the F1 finding-#1 closure: the alarm-by-entities read is scoped to the entity
 * ids returned by the permission-scoped {@code fetchEntities}, never to ids taken straight from config.
 */
@SpringBootTest(classes = {SimpleMeterRegistry.class, DefaultStatsFactory.class, DefaultTbelInvokeService.class})
class AbstractReportServiceTest {

    @Autowired
    private TbelInvokeService tbelInvokeService;

    private final ReportDataService dataService = mock(ReportDataService.class);
    private AbstractReportService service;

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final DeviceId deviceId = new DeviceId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = new TestReportService(dataService);
    }

    @Test
    void collectEntityDatasBuildsRowsWithLatestAndInfoValues() {
        DataKey serial = dataKey("serialNumber", "attribute", "Serial");
        DataSource source = deviceDataSource(List.of(serial));
        EntityData entity = entity(deviceId,
                Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"), "label", tsv("Lab A"))),
                Map.entry(EntityKeyType.ATTRIBUTE, Map.of("serialNumber", tsv("SN-1"))));
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(entity));

        List<Map<String, String>> rows = service.collectEntityDatas(ctx(), source, null);

        assertThat(rows).hasSize(1);
        Map<String, String> row = rows.get(0);
        assertThat(row).containsEntry("Serial", "SN-1");
        assertThat(row).containsEntry("entityName", "Device A");
        assertThat(row).containsEntry("entityLabel", "Lab A");
        assertThat(row).containsEntry("id", deviceId.toString());
    }

    @Test
    void buildTsComponentDataProducesOneRowPerTimestamp() {
        DataKey temp = dataKey("temperature", "timeseries", "Temp");
        TimeseriesTableComponent component = new TimeseriesTableComponent();
        component.setDataSources(List.of(deviceDataSource(List.of(temp))));
        component.setTimewindow(timeWindow(Aggregation.NONE));
        component.setShowTimestamp(true);
        component.setTimestampLabel("Time");
        component.setTimestampPattern("yyyy-MM-dd HH:mm:ss");

        EntityData entity = entity(deviceId, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))));
        when(dataService.getTimeseries(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(tsEntry(1_000L, 21.5), tsEntry(2_000L, 22.5)));

        ComponentData data = service.buildTsComponentData(600, ctx(), component, entity);

        assertThat(data.getEntityDatas()).hasSize(2);
        assertThat(data.getEntityDatas())
                .allSatisfy(row -> assertThat(row).containsKey("Temp").containsKey("Time").containsEntry("entityName", "Device A"));
        assertThat(data.getEntityDatas().stream().map(r -> r.get("Temp")).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("21.5", "22.5");
    }

    @Test
    void collectEntityDatasFetchesAggregatedTimeseriesForAggregatedKeys() {
        DataKey power = dataKey("power", "timeseries", "Power");
        power.setAggregationType(Aggregation.AVG);
        power.setTimewindow(timeWindow(Aggregation.AVG));
        DataSource source = deviceDataSource(List.of(power));

        EntityData entity = entity(deviceId, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))));
        when(dataService.findEntityDataByQuery(any(), any())).thenReturn(page(entity));
        // The aggregated value is served through the aggregation-specific read, not the entity query's latest.
        when(dataService.findTimeseriesByQueries(any(), any(), any()))
                .thenReturn(List.of(new ReadTsKvQueryResult(0, List.of(tsEntry("power", 5_000L, 42.0)), 5_000L)));

        List<Map<String, String>> rows = service.collectEntityDatas(ctx(), source, null);

        verify(dataService).findTimeseriesByQueries(any(), any(), any());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Power", "42.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAlarmComponentDataScopesAlarmReadToFetchedEntityIds() {
        DeviceId entityIdA = new DeviceId(UUID.randomUUID());
        DeviceId entityIdB = new DeviceId(UUID.randomUUID());
        DataKey typeKey = dataKey("type", "alarm", "Type");
        DataKey severityKey = dataKey("severity", "alarm", "Severity");

        DataSource alarmSource = deviceDataSource(List.of(typeKey, severityKey));
        alarmSource.setAlarmFilterConfig(new AlarmFilterConfig());
        AlarmTableComponent component = new AlarmTableComponent();
        component.setAlarmSource(alarmSource);
        component.setTimewindow(timeWindow(Aggregation.NONE));

        // fetchEntities (permission-scoped) yields exactly these two ids.
        when(dataService.findEntityDataByQuery(any(), any()))
                .thenReturn(page(entity(entityIdA, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A")))),
                        entity(entityIdB, Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device B"))))));

        ArgumentCaptor<Collection<EntityId>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        Alarm alarm = Alarm.builder().type("High Temperature").severity(AlarmSeverity.CRITICAL).build();
        when(dataService.findAlarmDataByQueryForEntities(any(), idsCaptor.capture(), any()))
                .thenReturn(page(new AlarmData(alarm, entityIdA)));

        ComponentData data = service.buildAlarmComponentData(600, ctx(), component, null);

        // F1 finding #1: the alarm-by-entities read is scoped to the ids from the permission-scoped fetch.
        Set<UUID> scopedIds = idsCaptor.getValue().stream().map(EntityId::getId).collect(Collectors.toSet());
        assertThat(scopedIds).containsExactlyInAnyOrder(entityIdA.getId(), entityIdB.getId());

        assertThat(data.getEntityDatas()).hasSize(1);
        assertThat(data.getEntityDatas().get(0)).containsEntry("Type", "High Temperature");
        assertThat(data.getEntityDatas().get(0)).containsEntry("Severity", "CRITICAL");
    }

    @Test
    void unsupportedEntityAliasDegradesToEmptyWithoutThrowing() {
        // A PE-only alias (entity-group / group-name / state-entity) can't round-trip into a CE EntityFilter,
        // so it surfaces as an unresolvable alias id. The data layer must render empty, never throw — and must
        // not issue a query with a null filter. The ctx's config has no entity aliases, so this id resolves to
        // nothing.
        DataKey typeKey = dataKey("type", "alarm", "Type");
        DataSource alarmSource = DataSource.builder()
                .type(DataSourceType.ENTITY)
                .entityAliasId("pe-only-group-alias")
                .dataKeys(List.of(typeKey))
                .alarmFilterConfig(new AlarmFilterConfig())
                .build();
        AlarmTableComponent component = new AlarmTableComponent();
        component.setAlarmSource(alarmSource);
        component.setTimewindow(timeWindow(Aggregation.NONE));

        ComponentData alarmData = service.buildAlarmComponentData(600, ctx(), component, null);
        assertThat(alarmData.getEntityDatas()).isEmpty();

        DataSource entitySource = DataSource.builder()
                .type(DataSourceType.ENTITY).entityAliasId("pe-only-group-alias").dataKeys(List.of(typeKey)).build();
        assertThat(service.collectEntityDatas(ctx(), entitySource, null)).isEmpty();

        // No query is ever issued for the unresolvable alias.
        verifyNoInteractions(dataService);
    }

    @Test
    void tbelPostProcessingTransformsTheValue() {
        // A report author's post-processing script runs through the sandboxed TBEL engine and transforms the
        // rendered value (10 -> 20). Proves the ScriptType.REPORT_DATA_KEY_SCRIPT + evalScript/evalData path.
        DataKey temp = dataKey("temperature", "timeseries", "Temp");
        temp.setUsePostProcessing(true);
        temp.setPostFuncBody("return value * 2;");

        EntityData entity = entity(deviceId,
                Map.entry(EntityKeyType.ENTITY_FIELD, Map.of("name", tsv("Device A"))),
                Map.entry(EntityKeyType.TIME_SERIES, Map.of("temperature", tsv("10"))));

        try (TbReportCtx ctx = ctx()) {
            Map<String, String> row = service.toStringMap(entity, List.of(temp), ctx, null);
            assertThat(row).containsEntry("Temp", "20.0");
            // The compiled script id was cached on the ctx so ctx.close() releases it.
            assertThat(ctx.getScripts()).hasSize(1);
        }
    }

    // --- fixtures ---

    private TbReportCtx ctx() {
        PdfReportTemplateConfig config = new PdfReportTemplateConfig();
        config.setTimeDataPattern("yyyy-MM-dd HH:mm:ss");
        config.setEntityAliases(List.of());
        config.setFilters(List.of());
        return TbReportCtx.builder()
                .tenantId(tenantId)
                .configuration(config)
                .timeZone("UTC")
                .reportCreatedTime("2026-07-23T00:00:00Z")
                .tbelInvokeService(tbelInvokeService)
                .build();
    }

    private DataSource deviceDataSource(List<DataKey> dataKeys) {
        return DataSource.builder()
                .type(DataSourceType.DEVICE)
                .deviceId(deviceId.getId().toString())
                .dataKeys(dataKeys)
                .build();
    }

    private static DataKey dataKey(String name, String type, String label) {
        DataKey dataKey = new DataKey();
        dataKey.setName(name);
        dataKey.setType(type);
        dataKey.setLabel(label);
        return dataKey;
    }

    private static TimeWindowConfiguration timeWindow(Aggregation aggregation) {
        History history = new History();
        history.setHistoryType(3); // "none" -> [0,0]; the actual reads are mocked so the range is irrelevant
        history.setInterval(Interval.of(0L));
        AggregationConfiguration agg = new AggregationConfiguration();
        agg.setType(aggregation);
        agg.setLimit(100);
        TimeWindowConfiguration tw = new TimeWindowConfiguration();
        tw.setHistory(history);
        tw.setAggregation(agg);
        return tw;
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
        return tsEntry("temperature", ts, value);
    }

    private static TsKvEntry tsEntry(String key, long ts, double value) {
        return new BasicTsKvEntry(ts, new DoubleDataEntry(key, value));
    }

    @SafeVarargs
    private static <T> PageData<T> page(T... items) {
        return new PageData<>(List.of(items), 1, items.length, false);
    }

    /** A concrete, non-bean {@link AbstractReportService} whose {@code dataService} is the test mock. */
    private static final class TestReportService extends AbstractReportService {
        private TestReportService(ReportDataService dataService) {
            this.dataService = dataService;
        }

        @Override
        public ReportData generateReport(ReportTask task, TbReportCtx ctx) {
            return null;
        }

        @Override
        public TbReportFormat getFormat() {
            return TbReportFormat.PDF;
        }
    }
}
