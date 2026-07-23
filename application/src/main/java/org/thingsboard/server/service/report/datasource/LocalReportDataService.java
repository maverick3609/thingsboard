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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.TbResourceInfo;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
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
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.report.ReportService;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.resource.ImageService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.service.query.EntityQueryService;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationCallback;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.ValidationResultCode;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.service.telemetry.TbTelemetryService;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * In-process {@link ReportDataService} (PE {@code LocalReportDataService}). <b>The report engine's
 * permission boundary.</b>
 * <p>
 * Every method runs its query under the report task's reconstructed {@link SecurityUser}, read from the
 * trusted {@link TbReportCtx} through a single {@link #getSecurityUser(TbReportCtx)} chokepoint. The tenant
 * and user scope come exclusively from that ctx — never from the query, the entity id, or the image URI a
 * template might carry. Consequences: entity/alarm/timeseries reads are permission-scoped exactly as if the
 * report user had issued them via the REST API (same {@code EntityQueryService}/{@code AccessValidator}
 * paths); a report can never read another tenant's — or another user's out-of-scope — data.
 * <p>
 * Gated by {@code reports.renderer.enabled} like the rest of the engine, so the platform boots renderer-off
 * (the ctx provider that would inject this is likewise gated — they appear/absent together).
 * <p>
 * <b>CE adaptations from PE</b> (PE compiled against the PE service surface; CE differs):
 * <ul>
 *   <li>{@code findAlarmDataByQueryForEntities} — CE's {@code AlarmService} is 3-arg
 *       {@code (tenantId, query, entityIds)}; PE's {@code MergedUserPermissions}/{@code getUserPermissions()}
 *       have no CE analogue. The entity ids are pre-resolved through the permission-scoped entity queries, so
 *       the tenant-scoped alarm read stays correctly bounded.</li>
 *   <li>{@code findTimeseriesByQueries} — CE has no permission-checked read-queries service method, so this
 *       validates {@code READ_TELEMETRY} on the entity via {@link AccessValidator} (exactly as
 *       {@code DefaultTbTelemetryService.getTimeseries} does) before calling the dao
 *       {@link TimeseriesService#findAllByQueries}.</li>
 *   <li>{@code findReportTemplate} — passes the {@link ReportTemplate} straight into the 5-arg
 *       {@code checkPermission} (bound {@code HasTenantId}); PE's {@code (TenantEntity)} cast is a PE-only
 *       type.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class LocalReportDataService implements ReportDataService {

    private final EntityQueryService entityQueryService;
    private final AlarmService alarmService;
    private final TbTelemetryService tbTelemetryService;
    private final TimeseriesService timeseriesService;
    private final AccessValidator accessValidator;
    private final AccessControlService accessControlService;
    private final ReportTemplateService reportTemplateService;
    private final ImageService imageService;
    private final ReportService reportService;

    @Override
    public ReportTemplate findReportTemplate(ReportTemplateId templateId, TbReportCtx ctx) throws ThingsboardException {
        SecurityUser securityUser = getSecurityUser(ctx);
        ReportTemplate reportTemplate = checkNotNull(reportTemplateService.findReportTemplateById(securityUser.getTenantId(), templateId));
        accessControlService.checkPermission(securityUser, Resource.REPORT_TEMPLATE, Operation.READ, templateId, reportTemplate);
        return reportTemplate;
    }

    @Override
    public byte[] downloadImage(String type, String key, TbReportCtx ctx) throws ThingsboardException {
        SecurityUser securityUser = getSecurityUser(ctx);
        TenantId tenantId = "system".equals(type) ? TenantId.SYS_TENANT_ID : securityUser.getTenantId();
        TbResourceInfo imageInfo = checkNotNull(imageService.getImageInfoByTenantIdAndKey(tenantId, key));
        return checkNotNull(imageService.getImageData(imageInfo.getTenantId(), imageInfo.getId()));
    }

    @Override
    public byte[] downloadPublicImage(String publicKey, TbReportCtx ctx) throws ThingsboardException {
        getSecurityUser(ctx); // fail-closed: refuse any download without a trusted ctx user (public images are cross-tenant, scoped to the owning tenant)
        TbResourceInfo imageInfo = checkNotNull(imageService.getPublicImageInfoByKey(publicKey));
        return checkNotNull(imageService.getImageData(imageInfo.getTenantId(), imageInfo.getId()));
    }

    @Override
    public PageData<EntityData> findEntityDataByQuery(EntityDataQuery query, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        PageData<EntityData> result = entityQueryService.findEntityDataByQuery(securityUser, query);
        log.debug("[{}] findEntityDataByQuery returned {} entity(ies) for user [{}]",
                securityUser.getTenantId(), result != null ? result.getData().size() : 0, securityUser.getId());
        return result;
    }

    @Override
    public Long countEntitiesByQuery(EntityCountQuery query, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        long count = entityQueryService.countEntitiesByQuery(securityUser, query);
        log.debug("[{}] countEntitiesByQuery = {}", securityUser.getTenantId(), count);
        return count;
    }

    @Override
    public PageData<AlarmData> findAlarmDataByQuery(AlarmDataQuery query, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        PageData<AlarmData> result = entityQueryService.findAlarmDataByQuery(securityUser, query);
        log.debug("[{}] findAlarmDataByQuery returned {} alarm(s)",
                securityUser.getTenantId(), result != null ? result.getData().size() : 0);
        return result;
    }

    @Override
    public PageData<AlarmData> findAlarmDataByQueryForEntities(AlarmDataQuery query, Collection<EntityId> entityIds, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        // CE's AlarmService is 3-arg (tenantId, query, entityIds); it enforces the tenant boundary in SQL but,
        // unlike PE, carries no customer-scope arg. PRECONDITION: the caller MUST pass an `entityIds` set that
        // was already permission-scoped to this SecurityUser (i.e. resolved via findEntityDataByQuery above) —
        // for a customer user, passing unscoped in-tenant ids here would widen the customer boundary.
        return alarmService.findAlarmDataByQueryForEntities(securityUser.getTenantId(), query, entityIds);
    }

    @Override
    public Long countAlarmsByQuery(AlarmCountQuery query, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        long count = entityQueryService.countAlarmsByQuery(securityUser, query);
        log.debug("[{}] countAlarmsByQuery = {}", securityUser.getTenantId(), count);
        return count;
    }

    @Override
    public List<TsKvEntry> getTimeseries(EntityId entityId, List<String> keys, Long startTs, Long endTs, Interval interval,
                                         String timeZone, Aggregation agg, SortOrder.Direction sortOrder, Integer limit,
                                         boolean useStrictDataTypes, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        // TbTelemetryService.getTimeseries validates READ_TELEMETRY internally, but that validation does NOT
        // gate the read on its own result code — AccessValidator reports denial via onSuccess(non-OK), which
        // the service's callback ignores (it is safe in the REST path only because the controller pre-validates).
        // ts_kv reads are keyed by entityId, not tenant, so this pre-validation is the real permission boundary.
        checkReadTelemetryPermission(securityUser, entityId);
        log.debug("[{}] getTimeseries entity [{}], {} key(s)", securityUser.getTenantId(), entityId, keys != null ? keys.size() : 0);
        try {
            List<TsKvEntry> result = getUnchecked(tbTelemetryService.getTimeseries(entityId, keys, startTs, endTs,
                    interval.getIntervalType(), interval.getInterval(), timeZone, limit, agg,
                    sortOrder != null ? sortOrder.name() : null, useStrictDataTypes, securityUser));
            log.debug("[{}] getTimeseries entity [{}] returned {} entry(ies)",
                    securityUser.getTenantId(), entityId, result != null ? result.size() : 0);
            return result;
        } catch (ThingsboardException e) {
            throw new RuntimeException("Failed to read report timeseries for entity " + entityId, e);
        }
    }

    @Override
    public List<ReadTsKvQueryResult> findTimeseriesByQueries(EntityId entityId, List<ReadTsKvQuery> queries, TbReportCtx ctx) {
        SecurityUser securityUser = getSecurityUser(ctx);
        log.debug("[{}] findTimeseriesByQueries entity [{}], {} query(ies)",
                securityUser.getTenantId(), entityId, queries != null ? queries.size() : 0);
        // No CE service method combines a permission check with read-queries, so validate READ_TELEMETRY on
        // the entity for this SecurityUser, then read only if that validation passed. AccessValidator reports
        // denial/not-found via onSuccess(non-OK) (NOT onFailure), so the read is explicitly gated on the
        // result code — otherwise a denied validation would still read (ts_kv is keyed by entityId, not tenant).
        SettableFuture<List<ReadTsKvQueryResult>> future = SettableFuture.create();
        accessValidator.validate(securityUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                // Wrap the whole body: a synchronous throw here (e.g. timeseriesService.findAllByQueries NPEs on
                // null queries) would otherwise be swallowed by the validator's executor, leaving `future` never
                // completed and getUnchecked() blocking the render thread forever. Mirror DefaultTbTelemetryService.
                try {
                    if (validationResult.getResultCode() != ValidationResultCode.OK) {
                        future.setException(ValidationCallback.getException(validationResult));
                        return;
                    }
                    Futures.addCallback(timeseriesService.findAllByQueries(securityUser.getTenantId(), entityId, queries),
                            new FutureCallback<>() {
                                @Override
                                public void onSuccess(List<ReadTsKvQueryResult> result) {
                                    future.set(result);
                                }

                                @Override
                                public void onFailure(Throwable t) {
                                    future.setException(t);
                                }
                            }, MoreExecutors.directExecutor());
                } catch (Throwable t) {
                    future.setException(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });
        return getUnchecked(future);
    }

    @Override
    public Report createReport(Report report, byte[] data, TbReportCtx ctx) throws ThingsboardException {
        SecurityUser securityUser = getSecurityUser(ctx);
        accessControlService.checkPermission(securityUser, Resource.REPORT, Operation.CREATE);
        // Bind the report to the trusted tenant, not whatever tenant the caller put on the Report — a report is
        // always created in the task user's tenant (defense-in-depth against a cross-tenant write).
        report.setTenantId(securityUser.getTenantId());
        return reportService.createReport(report, data);
    }

    /**
     * The permission chokepoint. Reads the trusted {@link SecurityUser} from the ctx and fails closed if it is
     * absent — a data query with no security user would be an unscoped read, which must never happen. Every
     * public method funnels through here, so the SecurityUser (and thus the tenant/user scope) always comes
     * from the ctx the render was built with, never from attacker-influenced query/URI input.
     */
    private SecurityUser getSecurityUser(TbReportCtx ctx) {
        SecurityUser securityUser = ctx.getSecurityUser();
        if (securityUser == null) {
            throw new IllegalStateException("Report context has no security user; refusing to run a permission-scoped data query");
        }
        return securityUser;
    }

    private <T> T checkNotNull(T reference) throws ThingsboardException {
        if (reference == null) {
            throw new ThingsboardException("Requested item wasn't found!", ThingsboardErrorCode.ITEM_NOT_FOUND);
        }
        return reference;
    }

    /**
     * Validates {@code READ_TELEMETRY} on {@code entityId} for {@code securityUser} and throws (fails closed) if
     * not permitted. {@link AccessValidator#validate} signals denial/not-found by calling {@code onSuccess} with a
     * non-{@code OK} {@link ValidationResult} (not {@code onFailure}), so the result code is checked explicitly —
     * mirroring the platform's {@link ValidationCallback}. Blocks until validation completes.
     */
    private void checkReadTelemetryPermission(SecurityUser securityUser, EntityId entityId) {
        SettableFuture<ValidationResult> validated = SettableFuture.create();
        accessValidator.validate(securityUser, Operation.READ_TELEMETRY, entityId, new FutureCallback<>() {
            @Override
            public void onSuccess(ValidationResult validationResult) {
                if (validationResult.getResultCode() == ValidationResultCode.OK) {
                    validated.set(validationResult);
                } else {
                    validated.setException(ValidationCallback.getException(validationResult));
                }
            }

            @Override
            public void onFailure(Throwable t) {
                validated.setException(t);
            }
        });
        getUnchecked(validated);
    }

    /** Blocks on a future, restoring the interrupt flag and unwrapping the cause so callers see the real error. */
    private static <T> T getUnchecked(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

}
