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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.script.api.ScriptType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.SortOrder;
import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityFilter;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.SingleEntityFilter;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.DataReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;
import org.thingsboard.server.common.data.report.configuration.timewindow.History;
import org.thingsboard.server.common.data.report.configuration.timewindow.TimeWindowConfiguration;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.report.datasource.ReportDataService;
import org.thingsboard.server.service.report.render.DataSourceUtils;
import org.thingsboard.server.service.report.render.ReportQueryUtils;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.report.render.TimeIntervalCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The server-side data layer shared by every report render engine ({@link PdfReportService}; the CSV engine
 * follows in Task I). Turns a report's typed {@link DataSource}/{@link DataKey} config into the {@link
 * ComponentData} rows/variables the renderers consume: it fetches entities, timeseries (with aggregation) and
 * alarms through the permission-scoped {@link ReportDataService}, formats each value (timestamp formatting +
 * optional TBEL post-processing), and assembles per-component data. Ported from PE {@code
 * org.thingsboard.server.report.service.AbstractReportService}.
 * <p>
 * <b>Abstract, not a bean.</b> No {@code @Service}/{@code @ConditionalOnProperty} here — the concrete engine
 * ({@link PdfReportService}) carries the {@code reports.renderer.enabled} gate, so the renderer opt-in
 * invariant is preserved (the platform boots renderer-off; this class is never instantiated then). {@link
 * #dataService} is {@code @Lazy @Autowired} (PE-faithful) — resolved only when a concrete gated engine bean
 * exists.
 * <p>
 * <b>Security — this is a per-task-reviewed boundary:</b>
 * <ul>
 *   <li><b>Query scoping (closes F1 finding #1).</b> {@link #buildAlarmComponentData} reads alarms via {@code
 *       dataService.findAlarmDataByQueryForEntities(query, entityIds, ctx)}; CE's 3-arg {@code AlarmService}
 *       scopes by tenant only. Safety comes from the {@code entityIds} <b>always</b> being {@code
 *       entityDataMap.keySet()} built from {@link #fetchEntities} → {@code dataService.findEntityDataByQuery}
 *       (F1 tenant+customer permission-scoped to {@code ctx.securityUser}) — <b>never</b> ids from the
 *       template config. Do not "optimize" this to pass config ids directly.</li>
 *   <li><b>TBEL post-processing.</b> {@link #postProcess}/{@link #evalScript}/{@link #evalData} compile the
 *       report author's {@code dataKey.postFuncBody} and run it through TB's sandboxed {@link
 *       org.thingsboard.script.api.tbel.TbelInvokeService}, scoped to {@code ctx.getTenantId()}, with fixed
 *       arg names {@code ["time","value"]} — the same trust model a tenant admin already has for a rule-node
 *       TBEL script. Compiled script ids land in {@code ctx.getScripts()} so {@code ctx.close()} (F1) releases
 *       them. No raw eval / JS / reflection; no user expression is evaluated outside the sandbox.</li>
 * </ul>
 * <b>CE adaptations</b> (documented per method): PE's {@code EntityKeyType.fromName} and {@code
 * JacksonUtil.getByKeyPath} do not exist in CE — replaced by the local {@link #latestKeyType} mapping and
 * {@link JacksonUtil#getSafely} respectively; PE's {@code StateEntityOwnerFilter} short-circuit in {@link
 * #fetchEntities} is dropped (CE has no such filter — see {@link ReportQueryUtils}).
 */
@Slf4j
public abstract class AbstractReportService implements TbReportRenderService {

    /** Report alarm data-key names → the JSON field path on a serialized {@link AlarmData} (PE-verbatim). */
    private static final Map<String, String> ALARM_FIELD_ALIASES_MAP = Map.of(
            "startTime", "startTs",
            "endTime", "endTs",
            "ackTime", "ackTs",
            "clearTime", "clearTs",
            "assignTime", "assignTs",
            "originator", "originatorName",
            "originatorType", "originator.entityType");

    @Lazy
    @Autowired
    protected ReportDataService dataService;

    protected List<EntityData> fetchEntities(TbReportCtx ctx, DataSource dataSource, EntityId stateEntityId) {
        EntityFilter filter = ReportQueryUtils.buildEntityFilter(dataSource, ctx, stateEntityId);
        if (filter == null) {
            // R2b: entity-group / group-name / state-entity alias unsupported in CE (PE-only — see
            // ReportQueryUtils#buildAliasBasedFilter / Task H). Degrades to empty rather than querying with a
            // null filter, so the component renders empty instead of failing the report.
            return Collections.emptyList();
        }
        if (filter instanceof SingleEntityFilter singleEntityFilter && singleEntityFilter.getSingleEntity() == null) {
            return Collections.emptyList();
        }
        // R2b: PE also short-circuits on a StateEntityOwnerFilter with a null single entity — CE has no
        // StateEntityOwnerFilter (state-entity owner aliases are PE-only, see Task H); the null-filter guard
        // above already degrades that case to empty.
        return fetchEntityDataByQuery(pageLink -> ReportQueryUtils.toEntityDataQuery(dataSource, ctx, filter, pageLink), dataSource, ctx);
    }

    private List<EntityData> fetchEntityDataByQuery(Function<PageLink, EntityDataQuery> querySupplier, DataSource dataSource, TbReportCtx ctx) {
        List<DataKey> dataKeysWithAggr = getDataKeysWithAggr(dataSource);
        List<EntityData> data = new ArrayList<>();
        for (EntityData entityData : new PageDataIterable<>(link -> dataService.findEntityDataByQuery(querySupplier.apply(link), ctx), 1024)) {
            updateWithAggregatedData(ctx, dataKeysWithAggr, entityData);
            data.add(entityData);
        }
        return data;
    }

    private List<DataKey> getDataKeysWithAggr(DataSource dataSource) {
        List<DataKey> dataKeys = dataSource.getDataKeys();
        if (dataKeys != null) {
            return dataKeys.stream().filter(dataKey -> dataKey.getAggregationType() != null && dataKey.getAggregationType() != Aggregation.NONE).toList();
        }
        return Collections.emptyList();
    }

    private void updateWithAggregatedData(TbReportCtx ctx, List<DataKey> dataKeysWithAggregation, EntityData entityData) {
        if (!dataKeysWithAggregation.isEmpty()) {
            List<ReadTsKvQuery> queries = buildReadTsKvQueries(ctx, dataKeysWithAggregation);
            List<ReadTsKvQueryResult> result = dataService.findTimeseriesByQueries(entityData.getEntityId(), queries, ctx);
            for (ReadTsKvQueryResult queryResult : result) {
                List<TsKvEntry> queryResultData = queryResult.getData();
                if (queryResultData != null && !queryResultData.isEmpty()) {
                    entityData.getTimeseries().put(queryResultData.get(0).getKey(), queryResult.toTsValues());
                }
            }
        }
    }

    private List<ReadTsKvQuery> buildReadTsKvQueries(TbReportCtx ctx, List<DataKey> dataKeysWithAggregation) {
        List<ReadTsKvQuery> queries = new ArrayList<>();
        for (DataKey key : dataKeysWithAggregation) {
            TimeWindowConfiguration timeWindowConf = key.getTimewindow();
            String targetTimezone = StringUtils.isNotBlank(timeWindowConf.getTimezone()) ? timeWindowConf.getTimezone() : ctx.getTimeZone();
            TimeIntervalCalculator.TimeRange timeRange = TimeIntervalCalculator.getTimeRange(timeWindowConf, targetTimezone);
            queries.add(new BaseReadTsKvQuery(key.getName(), timeRange.startTs, timeRange.endTs, timeRange.endTs - timeRange.startTs, 1, key.getAggregationType()));
        }
        return queries;
    }

    protected List<Map<String, String>> collectEntityDatas(TbReportCtx ctx, DataSource dataSource, EntityId stateEntityId) {
        switch (dataSource.getType()) {
            case DEVICE, ENTITY -> {
            }
            default -> throw new IllegalArgumentException("Unknown data source type: " + dataSource.getType());
        }
        return fetchEntities(ctx, dataSource, stateEntityId).stream()
                .map(entityData -> toStringMap(entityData, dataSource.getDataKeys(), ctx, null))
                .collect(Collectors.toList());
    }

    protected ComponentData buildTsComponentData(int usablePageWidthPx, TbReportCtx ctx, TimeseriesTableComponent component, EntityData entity) {
        TimeWindowConfiguration timeWindowConf = component.getTimewindow();
        History historyConf = timeWindowConf.getHistory();
        String targetTimezone = StringUtils.isNotBlank(timeWindowConf.getTimezone()) ? timeWindowConf.getTimezone() : ctx.getTimeZone();
        TimeIntervalCalculator.TimeRange timeRange = TimeIntervalCalculator.getTimeRange(timeWindowConf, targetTimezone);
        Optional<DataSource> singleDataSource = ReportUtils.getSingleDataSource(component);
        if (singleDataSource.isEmpty()) {
            return new ComponentData(usablePageWidthPx);
        }
        List<DataKey> dataKeys = singleDataSource.get().getDataKeys();
        List<DataKey> latestDataKeys = singleDataSource.get().getLatestDataKeys();
        List<String> keys = dataKeys.stream().map(DataKey::getName).collect(Collectors.toList());
        List<TsKvEntry> result = dataService.getTimeseries(entity.getEntityId(), keys, timeRange.startTs, timeRange.endTs,
                historyConf.getInterval(), timeWindowConf.getTimezone(), timeWindowConf.getAggregation().getType(),
                SortOrder.Direction.DESC, timeWindowConf.getAggregation().getLimit(), false, ctx);
        SortOrder sortOrder = SortOrder.of("rawTs", SortOrder.Direction.DESC);
        List<Map<String, String>> entityDatas = collectTsData(dataKeys, latestDataKeys, entity, result, component, sortOrder, timeWindowConf.getTimezone(), ctx);
        Map<String, Object> variables = new HashMap<>(toStringMap(entity, dataKeys, ctx, timeWindowConf.getTimezone()));
        return new ComponentData(usablePageWidthPx, null, entityDatas, variables);
    }

    protected ComponentData buildAlarmComponentData(int usablePageWidthPx, TbReportCtx ctx, AlarmTableComponent component, EntityData stateEntity) {
        DataSource alarmSource = component.getAlarmSource();
        if (alarmSource == null) {
            return new ComponentData(usablePageWidthPx);
        }
        switch (alarmSource.getType()) {
            case DEVICE -> {
                if (alarmSource.getDeviceId() == null) {
                    return new ComponentData(usablePageWidthPx);
                }
            }
            case ENTITY -> {
                if (alarmSource.getEntityAliasId() == null) {
                    return new ComponentData(usablePageWidthPx);
                }
            }
            default -> {
                return new ComponentData(usablePageWidthPx);
            }
        }
        List<DataKey> alarmDataKeys = alarmSource.getDataKeys().stream().filter(dataKey -> dataKey.getType().equals("alarm")).collect(Collectors.toList());
        List<DataKey> latestDataKeys = alarmSource.getDataKeys().stream().filter(dataKey -> !dataKey.getType().equals("alarm")).collect(Collectors.toList());
        EntityId stateEntityId = stateEntity != null ? stateEntity.getEntityId() : null;
        List<EntityData> entityDataList = fetchEntities(ctx, alarmSource, stateEntityId);
        Map<EntityId, EntityData> entityDataMap = entityDataList.stream().collect(Collectors.toMap(EntityData::getEntityId, Function.identity()));
        List<Map<String, String>> entityDatas = new ArrayList<>();
        if (entityDataMap.isEmpty()) {
            return new ComponentData(usablePageWidthPx);
        }
        // SECURITY (F1 finding #1): the alarm read is scoped by entityDataMap.keySet(), which is built from
        // fetchEntities(...) above — the permission-scoped (tenant+customer) entity query under ctx.securityUser.
        // The entity ids therefore come from a scoped read, never from attacker-influenced config.
        for (AlarmData alarmData : new PageDataIterable<>(link -> dataService.findAlarmDataByQueryForEntities(
                ReportQueryUtils.toAlarmDataQuery(component, ctx, stateEntityId, link), entityDataMap.keySet(), ctx), 1024)) {
            Map<String, String> mergedData = toStringMap(alarmData, alarmDataKeys, ctx, component.getTimewindow().getTimezone());
            EntityData entityData = entityDataMap.get(alarmData.getEntityId());
            mergedData.putAll(toStringMap(entityData, latestDataKeys, ctx, component.getTimewindow().getTimezone()));
            entityDatas.add(mergedData);
        }
        Map<String, String> variables = new HashMap<>();
        if (stateEntity != null) {
            putEntityInfoData(stateEntity, variables);
        } else {
            putEntityInfoData(entityDataList.get(0), variables);
        }
        return new ComponentData(usablePageWidthPx, null, entityDatas, new HashMap<>(variables));
    }

    protected Map<String, String> toStringMap(EntityData entityData, List<DataKey> dataKeys, TbReportCtx ctx, String timezone) {
        Map<String, String> data = new HashMap<>();
        if (entityData != null) {
            putLatestValues(dataKeys, data, entityData.getLatest(), ctx, timezone);
            putTimeseriesValues(dataKeys, data, entityData.getTimeseries(), ctx, timezone);
            putEntityInfoData(entityData, data);
        }
        return data;
    }

    protected void putEntityInfoData(EntityData entityData, Map<String, String> data) {
        Optional<String> entityName = DataSourceUtils.getEntityLatestValue(entityData, EntityKeyType.ENTITY_FIELD, "name");
        Optional<String> entityLabel = DataSourceUtils.getEntityLatestValue(entityData, EntityKeyType.ENTITY_FIELD, "label");
        data.put("entityName", entityName.orElse(""));
        data.put("entityLabel", entityLabel.orElse(""));
        data.put("id", entityData.getEntityId().toString());
    }

    protected Map<String, String> toStringMap(AlarmData alarmData, List<DataKey> alarmDataKeys, TbReportCtx ctx, String timezone) {
        Map<String, String> data = new HashMap<>();
        JsonNode alarmDataJson = JacksonUtil.valueToTree(alarmData);
        for (DataKey alarmKey : alarmDataKeys) {
            String targetKey = alarmKey.getName();
            String value = null;
            if (targetKey.equals("assignee")) {
                value = getAssigneeDisplayName(alarmData);
            } else {
                targetKey = ALARM_FIELD_ALIASES_MAP.getOrDefault(targetKey, targetKey);
                // CE: PE uses JacksonUtil.getByKeyPath(node, "a.b"); CE has getSafely(node, "a", "b") — split the
                // dotted alias path into segments (e.g. "originator.entityType").
                JsonNode jsonValue = JacksonUtil.getSafely(alarmDataJson, targetKey.split("\\."));
                if (jsonValue != null) {
                    value = jsonValue.asText();
                }
            }
            if (value == null) {
                continue;
            }
            if (ReportUtils.ENTITY_TIME_FIELDS.contains(alarmKey.getName())) {
                data.put("rawTs_" + alarmKey.getLabel(), value);
            }
            data.put(alarmKey.getLabel(), formatValue(ctx, alarmKey, 0L, value, timezone));
        }
        Optional<String> entityName = DataSourceUtils.getAlarmLatestValue(alarmData, EntityKeyType.ENTITY_FIELD, "name");
        Optional<String> entityLabel = DataSourceUtils.getAlarmLatestValue(alarmData, EntityKeyType.ENTITY_FIELD, "label");
        data.put("entityName", entityName.orElse(""));
        data.put("entityLabel", entityLabel.orElse(""));
        return data;
    }

    protected List<EntityData> getSubReportEntities(TbReportCtx ctx, DataReportComponent component, EntityId stateEntityId) {
        List<EntityData> entities;
        Optional<DataSource> dataSource = ReportUtils.getSingleDataSource(component);
        if (dataSource.isEmpty()) {
            entities = new ArrayList<>();
            entities.add(null);
        } else {
            entities = fetchEntities(ctx, dataSource.get(), stateEntityId);
        }
        return entities;
    }

    protected void populateReportVars(ComponentData componentData, TbReportCtx ctx) {
        Map<String, Object> variables = componentData.getVariables();
        variables.put("reportCreatedTime", ctx.getReportCreatedTime());
    }

    private void putLatestValues(List<DataKey> dataKeys, Map<String, String> data, Map<EntityKeyType, Map<String, TsValue>> latest, TbReportCtx ctx, String timezone) {
        if (dataKeys == null) {
            return;
        }
        for (DataKey dataKey : dataKeys) {
            Map<String, TsValue> keyValueMap = latest.get(latestKeyType(dataKey.getType()));
            if (keyValueMap == null) {
                continue;
            }
            TsValue tsValue = keyValueMap.get(dataKey.getName());
            if (tsValue == null || tsValue.getValue() == null) {
                continue;
            }
            if (ReportUtils.ENTITY_TIME_FIELDS.contains(dataKey.getName())) {
                data.put("rawTs_" + dataKey.getLabel(), tsValue.getValue());
            }
            data.put(dataKey.getLabel(), formatValue(ctx, dataKey, tsValue.getTs(), tsValue.getValue(), timezone));
        }
    }

    private void putTimeseriesValues(List<DataKey> dataKeys, Map<String, String> data, Map<String, TsValue[]> timeseries, TbReportCtx ctx, String timezone) {
        if (dataKeys == null) {
            return;
        }
        List<DataKey> dataKeysWithAggregation = dataKeys.stream().filter(dataKey -> dataKey.getAggregationType() != null && dataKey.getAggregationType() != Aggregation.NONE).toList();
        for (DataKey dataKey : dataKeysWithAggregation) {
            timeseries.computeIfPresent(dataKey.getName(), (s, tsValues) -> {
                for (TsValue tsValue : tsValues) {
                    data.put(dataKey.getLabel(), formatValue(ctx, dataKey, tsValue.getTs(), tsValue.getValue(), timezone));
                }
                return tsValues;
            });
        }
    }

    private String getAssigneeDisplayName(AlarmData alarmData) {
        if (alarmData.getAssignee() != null) {
            return alarmData.getAssignee().getTitle();
        }
        if (alarmData.getAssigneeId() != null) {
            return "User deleted";
        }
        return "Unassigned";
    }

    private List<Map<String, String>> collectTsData(List<DataKey> dataKeys, List<DataKey> latestDataKeys, EntityData entity,
                                                    List<TsKvEntry> tsKvEntries, TimeseriesTableComponent component,
                                                    SortOrder sortOrder, String timezone, TbReportCtx ctx) {
        Optional<String> entityName = DataSourceUtils.getEntityLatestValue(entity, EntityKeyType.ENTITY_FIELD, "name");
        Optional<String> entityLabel = DataSourceUtils.getEntityLatestValue(entity, EntityKeyType.ENTITY_FIELD, "label");
        List<Map<String, String>> tsData = new ArrayList<>();
        Comparator<Long> tsComparator = sortOrder.getDirection() == SortOrder.Direction.ASC ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Map<Long, List<TsKvEntry>> groupedByTs = tsKvEntries.stream()
                .collect(Collectors.groupingBy(TsKvEntry::getTs, () -> new TreeMap<>(tsComparator), Collectors.toList()));
        groupedByTs.forEach((ts, entries) -> {
            Map<String, String> tsValues = new HashMap<>();
            tsValues.put("rawTs", ts.toString());
            if (component.isShowTimestamp()) {
                tsValues.put(component.getTimestampLabel(), ReportUtils.formatTimestamp(ts, component.getTimestampPattern(), ctx, timezone));
            }
            for (DataKey dataKey : dataKeys) {
                entries.stream().filter(tsKvEntry -> tsKvEntry.getKey().equals(dataKey.getName())).findFirst().ifPresentOrElse(tsKvEntry -> {
                    String value = tsKvEntry.getValueAsString();
                    if (value != null) {
                        tsValues.put(dataKey.getLabel(), formatValue(ctx, dataKey, tsKvEntry.getTs(), tsKvEntry.getValue(), false, timezone));
                    }
                }, () -> tsValues.putIfAbsent(dataKey.getLabel(), null));
            }
            putLatestValues(latestDataKeys, tsValues, entity.getLatest(), ctx, timezone);
            tsValues.put("entityName", entityName.orElse(""));
            tsValues.put("entityLabel", entityLabel.orElse(""));
            tsData.add(tsValues);
        });
        return tsData;
    }

    private String formatValue(TbReportCtx ctx, DataKey dataKey, long timestamp, Object value, String timeZone) {
        return formatValue(ctx, dataKey, timestamp, value, true, timeZone);
    }

    private String formatValue(TbReportCtx ctx, DataKey dataKey, long timestamp, Object value, boolean parseString, String timezone) {
        if (dataKey == null) {
            return value != null ? value.toString() : null;
        }
        if (ReportUtils.ENTITY_TIME_FIELDS.contains(dataKey.getName()) && !dataKey.isUsePostProcessing() && value != null) {
            value = ReportUtils.formatTimestamp(value.toString(), ctx.getConfiguration().getTimeDataPattern(), ctx, timezone);
        }
        Object processed = postProcess(ctx, dataKey, timestamp, value, parseString);
        if (processed == null) {
            return null;
        }
        return processed.toString();
    }

    private Object postProcess(TbReportCtx ctx, DataKey dataKey, long timestamp, Object value, boolean parseString) {
        if (dataKey.isUsePostProcessing()) {
            Object input = parseString && value instanceof String ? ReportUtils.convertStringToTypedValue((String) value) : value;
            UUID scriptId = ctx.getScripts().computeIfAbsent(dataKey.getPostFuncBody(), s -> evalScript(ctx, s));
            if (scriptId != null) {
                return evalData(ctx, timestamp, input, scriptId);
            }
        }
        return value;
    }

    private Object evalData(TbReportCtx ctx, long timestamp, Object value, UUID scriptId) {
        try {
            return ctx.getTbelInvokeService().invokeScript(ctx.getTenantId(), null, scriptId, new Object[]{timestamp, value}).get();
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to evaluate data: " + value, e);
        } catch (ExecutionException e) {
            String error = "Failed to evaluate data: " + value;
            log.error(error, e);
            return error;
        }
    }

    private UUID evalScript(TbReportCtx ctx, String script) {
        try {
            return ctx.getTbelInvokeService().eval(ctx.getTenantId(), ScriptType.REPORT_DATA_KEY_SCRIPT, script, "time", "value").get();
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to compile script: " + script, e);
        } catch (ExecutionException e) {
            log.error("Failed to compile script {} ", script, e);
            return null;
        }
    }

    /**
     * CE replacement for PE's {@code EntityKeyType.fromName(String)} (absent in CE): maps a report {@link
     * DataKey#getType() data-key type} to the {@link EntityKeyType} its latest value is stored under —
     * mirroring {@link ReportQueryUtils#toEntityDataQuery}'s projection ({@code attribute}→ATTRIBUTE,
     * {@code timeseries}→TIME_SERIES, {@code entityField}→ENTITY_FIELD). Any other type falls back to
     * ENTITY_FIELD (no latest entry will match, so the value is simply skipped).
     */
    private static EntityKeyType latestKeyType(String dataKeyType) {
        return switch (dataKeyType) {
            case "attribute" -> EntityKeyType.ATTRIBUTE;
            case "timeseries" -> EntityKeyType.TIME_SERIES;
            default -> EntityKeyType.ENTITY_FIELD;
        };
    }

}
