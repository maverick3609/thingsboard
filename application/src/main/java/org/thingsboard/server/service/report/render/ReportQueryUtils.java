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

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.query.AliasEntityId;
import org.thingsboard.server.common.data.query.AlarmDataPageLink;
import org.thingsboard.server.common.data.query.AlarmDataQuery;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityDataSortOrder;
import org.thingsboard.server.common.data.query.EntityFilter;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.KeyFilter;
import org.thingsboard.server.common.data.query.SingleEntityFilter;
import org.thingsboard.server.common.data.report.configuration.AlarmFilterConfig;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.DataSourceType;
import org.thingsboard.server.common.data.report.configuration.EntityAlias;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.AlarmTableComponent;
import org.thingsboard.server.service.report.context.TbReportCtx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the entity/alarm {@code query} objects the report data layer ({@link AbstractReportService}) runs
 * through {@link org.thingsboard.server.service.report.datasource.ReportDataService}, from a report's typed
 * {@link DataSource}/{@link AlarmTableComponent} config. Ported from PE {@code
 * org.thingsboard.server.report.util.ReportQueryUtils}.
 * <p>
 * <b>CE adaptations (documented per the F2 design note):</b>
 * <ul>
 *   <li><b>Entity-alias resolution.</b> PE's {@code buildAliasBasedFilter} injects a resolved
 *       state/originator entity into {@code StateEntityFilter}/{@code StateEntityOwnerFilter}/{@code
 *       EntityGroupFilter}/{@code EntitiesByGroupNameFilter}/scheduler alias filters. CE has <b>none</b> of
 *       those filter types (dashboard state-entity, entity-group and scheduler aliases are PE-only), so that
 *       state-entity injection is dropped. CE's {@link EntityFilter#resolveEntityFilter} still resolves the
 *       default root entity for the alias types CE supports (single/relations/entity-search). The {@code
 *       stateEntityId}/originator parameter is threaded but inert until Task H wires sub-report/originator
 *       scoping.</li>
 *   <li><b>Count queries.</b> PE's {@code toEntityCountQuery}/{@code toAlarmCountQuery} are not ported here —
 *       they serve the single-datasource count builds the PDF dispatcher adds in Task G.</li>
 * </ul>
 * <b>Permission scoping is unchanged:</b> whichever filter is built, the query is executed by {@code
 * ReportDataService} under the task's trusted {@code SecurityUser} (F1), so a report can only read what its
 * user is permitted to read.
 */
@Slf4j
public final class ReportQueryUtils {

    private static final EntityDataSortOrder DEFAULT_SORT_ORDER =
            new EntityDataSortOrder(new EntityKey(EntityKeyType.ENTITY_FIELD, "id"), EntityDataSortOrder.Direction.ASC);
    private static final EntityDataSortOrder DEFAULT_ALARM_SORT_ORDER =
            new EntityDataSortOrder(new EntityKey(EntityKeyType.ALARM_FIELD, "createdTime"), EntityDataSortOrder.Direction.DESC);

    private ReportQueryUtils() {
    }

    public static AlarmDataQuery toAlarmDataQuery(AlarmTableComponent component, TbReportCtx ctx, EntityId stateEntityId, PageLink pageLink) {
        DataSource alarmSource = component.getAlarmSource();
        EntityFilter entityFilter = buildEntityFilter(alarmSource, ctx, stateEntityId);
        List<KeyFilter> keyFilters = findKeyFilters(alarmSource, ctx.getConfiguration());
        List<EntityKey> alarmFields = alarmSource.getDataKeys().stream()
                .filter(dataKey -> "alarm".equals(dataKey.getType()))
                .map(dataKey -> new EntityKey(EntityKeyType.ALARM_FIELD, dataKey.getName())).toList();
        List<EntityKey> entityFields = alarmSource.getDataKeys().stream()
                .filter(dataKey -> "entityField".equals(dataKey.getType()))
                .map(dataKey -> new EntityKey(EntityKeyType.ENTITY_FIELD, dataKey.getName())).toList();
        entityFields = DataSourceUtils.setEntityKeyIfNotExists(entityFields, EntityKeyType.ENTITY_FIELD, "name");
        entityFields = DataSourceUtils.setEntityKeyIfNotExists(entityFields, EntityKeyType.ENTITY_FIELD, "label");
        List<EntityKey> attrFields = alarmSource.getDataKeys().stream()
                .filter(dataKey -> "attribute".equals(dataKey.getType()))
                .map(dataKey -> new EntityKey(EntityKeyType.ATTRIBUTE, dataKey.getName())).toList();
        List<EntityKey> tsFields = alarmSource.getDataKeys().stream()
                .filter(dataKey -> "timeseries".equals(dataKey.getType()))
                .map(dataKey -> new EntityKey(EntityKeyType.TIME_SERIES, dataKey.getName())).toList();
        List<EntityKey> latestValues = Stream.concat(attrFields.stream(), tsFields.stream()).toList();
        AlarmFilterConfig alarmFilterConfig = alarmSource.getAlarmFilterConfig();
        AlarmDataPageLink alarmDataPageLink = new AlarmDataPageLink();
        alarmDataPageLink.setPage(pageLink.getPage());
        alarmDataPageLink.setPageSize(pageLink.getPageSize());
        alarmDataPageLink.setSortOrder(DEFAULT_ALARM_SORT_ORDER);
        String targetTimezone = StringUtils.isNotBlank(component.getTimewindow().getTimezone())
                ? component.getTimewindow().getTimezone() : ctx.getTimeZone();
        TimeIntervalCalculator.TimeRange timeRange = TimeIntervalCalculator.getTimeRange(component.getTimewindow(), targetTimezone);
        alarmDataPageLink.setStartTs(timeRange.startTs);
        alarmDataPageLink.setEndTs(timeRange.endTs);
        alarmDataPageLink.setSearchPropagatedAlarms(alarmFilterConfig.isSearchPropagatedAlarms());
        alarmDataPageLink.setSeverityList(alarmFilterConfig.getSeverityList());
        alarmDataPageLink.setStatusList(alarmFilterConfig.getStatusList());
        alarmDataPageLink.setTypeList(alarmFilterConfig.getTypeList());
        alarmDataPageLink.setAssigneeId(alarmFilterConfig.getAssigneeId());
        return new AlarmDataQuery(entityFilter, alarmDataPageLink, entityFields, latestValues, keyFilters, alarmFields);
    }

    public static EntityDataQuery toEntityDataQuery(DataSource dataSource, TbReportCtx ctx, EntityFilter filter, PageLink pageLink) {
        EntityDataPageLink entityDataPageLink = new EntityDataPageLink(pageLink.getPageSize(), pageLink.getPage(), pageLink.getTextSearch(), DEFAULT_SORT_ORDER);
        List<KeyFilter> keyFilters = findKeyFilters(dataSource, ctx.getConfiguration());
        List<EntityKey> entityFields = new ArrayList<>();
        List<EntityKey> latestValues = new ArrayList<>();
        if (dataSource.getDataKeys() != null) {
            for (DataKey dataKey : dataSource.getDataKeys()) {
                switch (dataKey.getType()) {
                    case "attribute" -> latestValues.add(new EntityKey(EntityKeyType.ATTRIBUTE, dataKey.getName()));
                    case "timeseries" -> {
                        if (dataKey.getAggregationType() == null || dataKey.getAggregationType() == Aggregation.NONE) {
                            latestValues.add(new EntityKey(EntityKeyType.TIME_SERIES, dataKey.getName()));
                        }
                    }
                    case "entityField" -> entityFields.add(new EntityKey(EntityKeyType.ENTITY_FIELD, dataKey.getName()));
                    default -> {
                    }
                }
            }
        }
        entityFields = DataSourceUtils.setEntityKeyIfNotExists(entityFields, EntityKeyType.ENTITY_FIELD, "name");
        entityFields = DataSourceUtils.setEntityKeyIfNotExists(entityFields, EntityKeyType.ENTITY_FIELD, "label");
        return new EntityDataQuery(filter, entityDataPageLink, entityFields, latestValues, keyFilters);
    }

    public static EntityFilter buildEntityFilter(DataSource dataSource, TbReportCtx ctx, EntityId stateEntityId) {
        if (dataSource.getType() == DataSourceType.DEVICE) {
            return buildSingleEntityFilter(DeviceId.fromString(dataSource.getDeviceId()));
        }
        return buildAliasBasedFilter(dataSource, ctx, stateEntityId);
    }

    private static EntityFilter buildSingleEntityFilter(EntityId entityId) {
        SingleEntityFilter filter = new SingleEntityFilter();
        filter.setSingleEntity(AliasEntityId.fromEntityId(entityId));
        return filter;
    }

    /**
     * Resolves the data source's entity-alias filter from the report config and lets CE resolve its default
     * root entity. Returns {@code null} — which {@link AbstractReportService#fetchEntities} degrades to an
     * empty result — when the alias can't be resolved, so a report never crashes on an alias the CE data
     * layer can't express. {@code stateEntityId} is reserved for Task H (originator/state threading).
     */
    private static EntityFilter buildAliasBasedFilter(DataSource dataSource, TbReportCtx ctx, EntityId stateEntityId) {
        EntityFilter filter = Optional.ofNullable(ctx.getConfiguration().getEntityAliases()).orElseGet(List::of).stream()
                .filter(alias -> alias.getId().equals(dataSource.getEntityAliasId()))
                .map(EntityAlias::getFilter)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (filter == null) {
            // R2b: entity-group / group-name / state-entity aliases unsupported in CE (PE-only subsystems —
            // no CE EntityFilter subtype and no EntityGroup service to resolve against; state-entity threading
            // is Task H). Such an alias can't round-trip into a CE filter, so it surfaces here as unresolvable
            // and degrades to empty (never throws), matching R2a's graceful-degradation posture.
            log.debug("Entity alias [{}] not resolvable in CE — data source renders empty", dataSource.getEntityAliasId());
            return null;
        }
        EntityFilter.resolveEntityFilter(filter, ctx.getTenantId(), ctx.getUserId(), ctx.getUserOwnerId());
        return filter;
    }

    private static List<KeyFilter> findKeyFilters(DataSource dataSource, ReportTemplateConfig reportTemplateConfig) {
        if (dataSource.getFilterId() != null) {
            return reportTemplateConfig.getFilters().stream()
                    .filter(filter -> filter.getId().equals(dataSource.getFilterId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Entity filter not found: " + dataSource.getFilterId()))
                    .getKeyFilters();
        }
        return null;
    }
}
