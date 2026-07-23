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
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.report.ReportService;
import org.thingsboard.server.dao.report.ReportTemplateService;
import org.thingsboard.server.dao.resource.ImageService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.service.query.EntityQueryService;
import org.thingsboard.server.service.report.context.TbReportCtx;
import org.thingsboard.server.service.security.AccessValidator;
import org.thingsboard.server.service.security.ValidationResult;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.telemetry.TbTelemetryService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the {@link LocalReportDataService#findTimeseriesByQueries} hang class (F1 review,
 * MEDIUM). The read runs inside the {@code AccessValidator} callback; if that inner call throws
 * <b>synchronously</b> (e.g. the DAO NPEs on a malformed query) the exception is swallowed by the validator's
 * executor. Without the {@code try/catch} that completes the {@code SettableFuture} on any throwable, the
 * outer {@code future.get()} would block the render thread forever — a slow reporting-worker starvation.
 * <p>
 * Mockito-only (no Spring/DB): the nine collaborators are stubbed so only the callback wiring is exercised.
 * {@code @Test(timeout)} makes a regression <b>fail</b> (JUnit interrupts the stuck thread) instead of
 * hanging the whole suite.
 */
public class LocalReportDataServiceHangGuardTest {

    private AccessValidator accessValidator;
    private TimeseriesService timeseriesService;
    private LocalReportDataService service;

    @Before
    public void setUp() {
        EntityQueryService entityQueryService = mock(EntityQueryService.class);
        AlarmService alarmService = mock(AlarmService.class);
        TbTelemetryService tbTelemetryService = mock(TbTelemetryService.class);
        timeseriesService = mock(TimeseriesService.class);
        accessValidator = mock(AccessValidator.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        ReportTemplateService reportTemplateService = mock(ReportTemplateService.class);
        ImageService imageService = mock(ImageService.class);
        ReportService reportService = mock(ReportService.class);
        service = new LocalReportDataService(entityQueryService, alarmService, tbTelemetryService,
                timeseriesService, accessValidator, accessControlService, reportTemplateService,
                imageService, reportService);
    }

    @Test(timeout = 30000)
    public void findTimeseriesByQueriesFailsClosedWhenInnerReadThrowsSynchronously() {
        SecurityUser user = mock(SecurityUser.class);
        when(user.getTenantId()).thenReturn(TenantId.SYS_TENANT_ID);
        TbReportCtx ctx = TbReportCtx.builder().securityUser(user).build();

        // Validation passes -> the callback runs the inner read...
        doAnswer(inv -> {
            FutureCallback<ValidationResult> cb = inv.getArgument(3);
            cb.onSuccess(ValidationResult.ok(null));
            return null;
        }).when(accessValidator).validate(eq(user), eq(Operation.READ_TELEMETRY), any(EntityId.class), any());

        // ...which throws synchronously (the swallow-and-hang trigger).
        when(timeseriesService.findAllByQueries(any(), any(), any()))
                .thenThrow(new IllegalStateException("synchronous DAO failure"));

        // With the fix the future is completed exceptionally, so the call returns (throws) rather than hanging;
        // without it, @Test(timeout) trips instead.
        assertThatThrownBy(() -> service.findTimeseriesByQueries(
                new DeviceId(UUID.randomUUID()),
                List.of(new BaseReadTsKvQuery("temperature", 0L, 1L)),
                ctx))
                .isInstanceOf(RuntimeException.class);
    }
}
