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
package org.thingsboard.server.service.report.datasource;

import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQueryResult;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.SortOrder;
import org.thingsboard.server.common.data.query.AlarmCountQuery;
import org.thingsboard.server.common.data.query.AlarmData;
import org.thingsboard.server.common.data.query.AlarmDataQuery;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.configuration.timewindow.Interval;
import org.thingsboard.server.service.report.context.TbReportCtx;

import java.util.Collection;
import java.util.List;

/**
 * The server-side data-access seam the report render engine queries through (PE {@code
 * report.datasource.ReportDataService}). R1/R2a deferred this — R2a's renderers had no way to read
 * entity/alarm/timeseries data; R2b's table renderers + sub-reports do, and they fetch it here.
 * <p>
 * <b>This is a permission boundary.</b> Every method takes the {@link TbReportCtx} for the running
 * report task and runs the query under the task's reconstructed, trusted {@code SecurityUser} (the
 * {@code Local} impl reads it from the ctx via a single {@code getSecurityUser(ctx)} chokepoint). The
 * tenant/user scope therefore comes from the ctx, never from attacker-influenced input (the template
 * config, an entity id in a query, or an image URI). A report can only ever read what the task user is
 * permitted to read.
 * <p>
 * PE ships two impls — {@code LocalReportDataService} (in-process) and {@code RemoteReportDataService}
 * (the standalone {@code tb-report} microservice). Inferrix runs the renderer in-process only, so R2b
 * ships {@link org.thingsboard.server.service.report.datasource.LocalReportDataService} alone.
 */
public interface ReportDataService {

    ReportTemplate findReportTemplate(ReportTemplateId templateId, TbReportCtx ctx) throws ThingsboardException;

    byte[] downloadImage(String type, String key, TbReportCtx ctx) throws ThingsboardException;

    byte[] downloadPublicImage(String publicKey, TbReportCtx ctx) throws ThingsboardException;

    PageData<EntityData> findEntityDataByQuery(EntityDataQuery query, TbReportCtx ctx);

    Long countEntitiesByQuery(EntityCountQuery query, TbReportCtx ctx);

    PageData<AlarmData> findAlarmDataByQuery(AlarmDataQuery query, TbReportCtx ctx);

    PageData<AlarmData> findAlarmDataByQueryForEntities(AlarmDataQuery query, Collection<EntityId> entityIds, TbReportCtx ctx);

    Long countAlarmsByQuery(AlarmCountQuery query, TbReportCtx ctx);

    List<TsKvEntry> getTimeseries(EntityId entityId, List<String> keys, Long startTs, Long endTs, Interval interval,
                                  String timeZone, Aggregation agg, SortOrder.Direction sortOrder, Integer limit,
                                  boolean useStrictDataTypes, TbReportCtx ctx);

    List<ReadTsKvQueryResult> findTimeseriesByQueries(EntityId entityId, List<ReadTsKvQuery> queries, TbReportCtx ctx);

    Report createReport(Report report, byte[] data, TbReportCtx ctx) throws ThingsboardException;

}
