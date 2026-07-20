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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.common.util.ThingsBoardExecutors;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.dashboardreport.DashboardReportConfig;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.report.DashboardReportService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.utils.MiscUtils;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * On-demand dashboard-report download surface (design spec §10, task 21): "render the dashboard I'm
 * looking at right now" ({@code /report/{dashboardId}/download}) and its test-render counterpart
 * ({@code /report/test}). Reconciled against this fork's actual {@link DashboardReportService}
 * (synchronous, 2-arg, mints the access token and renders internally - task 13) rather than PE's
 * decompiled 10-arg async-callback {@code generateDashboardReport}/{@code generateReport}, which
 * don't exist here.
 * <p>
 * {@link DashboardReportService} is {@code @ConditionalOnProperty(reports.renderer.enabled)}
 * ({@link org.thingsboard.server.service.report.DefaultDashboardReportService}), so it's injected as
 * an {@link Optional} - same reason as {@link ReportController}'s {@code Optional<TbReportService>}
 * (task 20): without the {@link Optional}, Spring would fail to wire this controller in any
 * environment that hasn't opted into the renderer.
 * <p>
 * {@code generateReport} blocks for as long as the render takes (up to ~120s per the renderer's
 * design), so it must never run on the request thread: both endpoints return a
 * {@link DeferredResult} and offload the actual call to {@link #reportExecutor}, a dedicated pool
 * owned by this controller (mirrors {@code DefaultMailService}'s executor field / {@code
 * AbstractListeningExecutor}'s executor-plus-{@code @PreDestroy} idiom). The
 * {@link DeferredResult} timeout is set past the renderer's worst case so a legitimately slow render
 * isn't cut off by Spring's default 30s ({@code spring.mvc.async.request-timeout}).
 */
@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class DashboardReportController extends BaseController {

    public static final String DASHBOARD_ID = "dashboardId";

    private static final long REPORT_DEFERRED_RESULT_TIMEOUT_MS = 150_000L;

    // DashboardReportService is @ConditionalOnProperty(reports.renderer.enabled) - Optional so this
    // controller still wires when the renderer is off.
    private final Optional<DashboardReportService> dashboardReportService;

    private final ExecutorService reportExecutor = ThingsBoardExecutors.newLimitedTasksExecutor(4, 100, "dashboard-report-controller");

    @PreDestroy
    public void stop() {
        reportExecutor.shutdown();
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/report/{dashboardId}/download", produces = {"application/pdf", "image/jpeg", "image/png"})
    public DeferredResult<ResponseEntity<Resource>> downloadDashboardReport(@PathVariable(DASHBOARD_ID) String strDashboardId,
                                                                             @RequestBody JsonNode reportParams,
                                                                             HttpServletRequest request) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST downloadDashboardReport [{}]", currentUser.getTenantId(), currentUser.getId(), strDashboardId);
        checkParameter(DASHBOARD_ID, strDashboardId);
        DashboardId dashboardId = new DashboardId(toUUID(strDashboardId));
        // Authorizes AND gives the dashboard title for namePattern.
        DashboardInfo dashboardInfo = checkDashboardInfoId(dashboardId, Operation.READ);

        DeferredResult<ResponseEntity<Resource>> result = new DeferredResult<>(REPORT_DEFERRED_RESULT_TIMEOUT_MS);
        DashboardReportService svc = requireRenderer(result);
        if (svc == null) {
            return result;
        }

        DashboardReportConfig config = new DashboardReportConfig();
        config.setBaseUrl(MiscUtils.constructBaseUrl(request));
        config.setDashboardId(strDashboardId);
        config.setType(textOrNull(reportParams, "type"));
        config.setTimezone(textOrNull(reportParams, "timezone"));
        config.setState(textOrNull(reportParams, "state"));
        JsonNode timewindow = reportParams != null && reportParams.hasNonNull("timewindow") ? reportParams.get("timewindow") : null;
        config.setTimewindow(timewindow);
        config.setUseDashboardTimewindow(timewindow == null);
        // Fresh SimpleDateFormat per call - this controller bean is a singleton and
        // SimpleDateFormat is not thread-safe, so a shared field would corrupt concurrent requests'
        // formatted timestamps.
        config.setNamePattern(dashboardInfo.getTitle() + "-" + new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date()));
        config.setUseCurrentUserCredentials(true);
        config.setUserId(currentUser.getId().getId().toString());

        TenantId tenantId = currentUser.getTenantId();
        offloadRender(svc, tenantId, config, result);
        return result;
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/report/test", produces = {"application/pdf", "image/jpeg", "image/png"})
    public DeferredResult<ResponseEntity<Resource>> downloadTestReport(@RequestBody DashboardReportConfig config,
                                                                        // In-process renderer: no separate report server to point at. Accepted only
                                                                        // for UI-payload parity with PE and otherwise ignored.
                                                                        @RequestParam(required = false) String reportsServerEndpointUrl,
                                                                        HttpServletRequest request) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST downloadTestReport [{}]", currentUser.getTenantId(), currentUser.getId(), config.getDashboardId());
        String strDashboardId = config.getDashboardId();
        checkParameter(DASHBOARD_ID, strDashboardId);
        checkDashboardInfoId(new DashboardId(toUUID(strDashboardId)), Operation.READ);

        DeferredResult<ResponseEntity<Resource>> result = new DeferredResult<>(REPORT_DEFERRED_RESULT_TIMEOUT_MS);
        DashboardReportService svc = requireRenderer(result);
        if (svc == null) {
            return result;
        }

        // Never trust client-supplied identity/base-url fields: the client controls this request
        // body wholesale (unlike downloadDashboardReport's server-built config), so both must be
        // forced from the authenticated principal / request to avoid minting a token for an
        // arbitrary userId (privilege escalation) or letting the renderer navigate to a
        // client-chosen baseUrl carrying that token (SSRF + token exfiltration).
        config.setUserId(currentUser.getId().getId().toString());
        config.setUseCurrentUserCredentials(true);
        config.setBaseUrl(MiscUtils.constructBaseUrl(request));

        TenantId tenantId = currentUser.getTenantId();
        offloadRender(svc, tenantId, config, result);
        return result;
    }

    private DashboardReportService requireRenderer(DeferredResult<ResponseEntity<Resource>> result) {
        if (dashboardReportService.isEmpty()) {
            result.setErrorResult(new ThingsboardException("Report renderer is not enabled", ThingsboardErrorCode.BAD_REQUEST_PARAMS));
            return null;
        }
        return dashboardReportService.get();
    }

    private void offloadRender(DashboardReportService svc, TenantId tenantId, DashboardReportConfig config,
                                DeferredResult<ResponseEntity<Resource>> result) {
        reportExecutor.execute(() -> {
            try {
                ReportData reportData = svc.generateReport(tenantId, config);
                result.setResult(buildDownloadResponse(reportData));
            } catch (IllegalArgumentException e) {
                // DefaultDashboardReportService#mintAccessToken throws this for an authority-less
                // user - map it to a clean 4xx instead of letting it surface as an opaque 500.
                result.setErrorResult(new ThingsboardException(e.getMessage(), ThingsboardErrorCode.BAD_REQUEST_PARAMS));
            } catch (Exception e) {
                result.setErrorResult(e);
            }
        });
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private ResponseEntity<Resource> buildDownloadResponse(ReportData reportData) {
        ByteArrayResource resource = new ByteArrayResource(reportData.getData());
        // filename is derived from the dashboard title (user-controlled); RFC-6266-encode it for
        // Content-Disposition rather than hand-splicing it into the header value, and strip
        // CR/LF/control characters for the custom x-filename header, so neither is a header-injection
        // vector (mirrors ReportController#buildDownloadResponse).
        String contentDisposition = ContentDisposition.attachment()
                .filename(reportData.getName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        String sanitizedFilename = reportData.getName().replaceAll("\\p{Cntrl}", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header("x-filename", sanitizedFilename)
                .contentLength(resource.contentLength())
                .contentType(parseMediaType(reportData.getContentType()))
                .body(resource);
    }

}
