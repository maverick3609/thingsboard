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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.JobManager;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.job.Job;
import org.thingsboard.server.common.data.job.JobType;
import org.thingsboard.server.common.data.job.ReportJobConfiguration;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.ReportInfoQuery;
import org.thingsboard.server.common.data.report.ReportRequest;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.report.TbReportService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code /api/v2} report-history + on-demand-generation REST surface (design spec §10, task 20).
 * Ports PE's {@code ReportController}, reconciled against this fork's actual substrate rather than
 * PE's decompiled bytecode verbatim — see the task-20 report for the point-by-point reconciliation
 * (token minting, owner resolution, synchronous test-render, {@link Job} construction, the
 * {@code userId} string-to-{@link UserId} parse, and the {@link Optional}-guarded {@link
 * TbReportService} dependency).
 * <p>
 * {@link #testReport} and {@link #requestReport} intentionally do NOT call {@code
 * accessControlService.checkPermission} directly: {@link #checkReportTemplateId} (invoked by both)
 * is itself the per-entity authorization boundary, exactly mirroring PE.
 */
@RestController
@TbCoreComponent
@RequestMapping("/api/v2")
@RequiredArgsConstructor
@Slf4j
public class ReportController extends BaseController {

    public static final String REPORT_ID = "reportId";

    private final JobManager jobManager;
    // TbReportService is @ConditionalOnProperty(reports.renderer.enabled) - Optional so this
    // controller (and its history endpoints) still wires when the renderer is off.
    private final Optional<TbReportService> tbReportService;
    private final SystemSecurityService systemSecurityService;
    private final JwtTokenFactory jwtTokenFactory;

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping("/report")
    public Report createReport(@RequestPart MultipartFile file, @RequestPart String info) throws Exception {
        SecurityUser currentUser = getCurrentUser();
        Report report = JacksonUtil.fromString(info, Report.class);
        log.debug("[{}][{}] REST createReport [{}]", currentUser.getTenantId(), currentUser.getId(), report.getName());
        // Deviation from PE (which trusts info's tenantId/customerId as-is): the real permission
        // checkers (TenantAdminPermissions#tenantEntityPermissionChecker,
        // CustomerUserPermissions#reportPermissionChecker) compare entity.getTenantId()/
        // getCustomerId() against the caller's own, so an omitted/forged value here would either
        // 403 the legitimate caller or trust client-supplied ownership. Force both from the
        // authenticated session, matching every other CE create endpoint's convention (e.g.
        // ReportTemplateController#saveReportTemplate for tenantId).
        report.setTenantId(currentUser.getTenantId());
        report.setCustomerId(currentUser.getCustomerId());
        accessControlService.checkPermission(currentUser, Resource.REPORT, Operation.CREATE, null, report);
        return reportService.createReport(report, file.getBytes());
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping("/report/{reportId}")
    public Report getReportById(@PathVariable(REPORT_ID) String strReportId) throws ThingsboardException {
        checkParameter(REPORT_ID, strReportId);
        ReportId reportId = new ReportId(toUUID(strReportId));
        log.debug("[{}][{}] REST getReportById [{}]", getTenantId(), getCurrentUser().getId(), reportId);
        return checkReportId(reportId, Operation.READ);
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping("/report/{reportId}/download")
    public ResponseEntity<ByteArrayResource> downloadReport(@PathVariable(REPORT_ID) String strReportId) throws ThingsboardException {
        checkParameter(REPORT_ID, strReportId);
        ReportId reportId = new ReportId(toUUID(strReportId));
        log.debug("[{}][{}] REST downloadReport [{}]", getTenantId(), getCurrentUser().getId(), reportId);
        Report report = checkReportId(reportId, Operation.READ);
        byte[] data = reportService.getReportData(getTenantId(), reportId);
        return buildDownloadResponse(report.getName(), report.getFormat().getContentType(), data);
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @DeleteMapping("/report/{reportId}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteReport(@PathVariable(REPORT_ID) String strReportId) throws ThingsboardException {
        checkParameter(REPORT_ID, strReportId);
        ReportId reportId = new ReportId(toUUID(strReportId));
        log.debug("[{}][{}] REST deleteReport [{}]", getTenantId(), getCurrentUser().getId(), reportId);
        checkReportId(reportId, Operation.DELETE);
        reportService.deleteReport(getTenantId(), reportId);
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/reportInfos", params = {"pageSize", "page"})
    public PageData<ReportInfo> getReportInfos(@RequestParam(required = false) UUID reportTemplateId,
                                                @RequestParam(required = false) UUID userId,
                                                @RequestParam(required = false) Boolean includeCustomers,
                                                @RequestParam int pageSize,
                                                @RequestParam int page,
                                                @RequestParam(required = false) String textSearch,
                                                @RequestParam(required = false) String sortProperty,
                                                @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        return findReportInfos(reportTemplateId, userId, includeCustomers, pageSize, page, textSearch, sortProperty, sortOrder);
    }

    // R1: tenant-wide listing only - the CE dao has one findReports(TenantId, ReportInfoQuery)
    // method (no customer-scoped overload), and spec §10 scopes both /reportInfos and
    // /reportInfos/all to TENANT_ADMIN, so this intentionally duplicates getReportInfos' body via
    // the shared findReportInfos helper rather than branching on authority (there's nothing to
    // branch on in R1).
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/reportInfos/all", params = {"pageSize", "page"})
    public PageData<ReportInfo> getAllReportInfos(@RequestParam(required = false) UUID reportTemplateId,
                                                   @RequestParam(required = false) UUID userId,
                                                   @RequestParam(required = false) Boolean includeCustomers,
                                                   @RequestParam int pageSize,
                                                   @RequestParam int page,
                                                   @RequestParam(required = false) String textSearch,
                                                   @RequestParam(required = false) String sortProperty,
                                                   @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        return findReportInfos(reportTemplateId, userId, includeCustomers, pageSize, page, textSearch, sortProperty, sortOrder);
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @PostMapping("/report/test")
    public ResponseEntity<ByteArrayResource> testReport(@RequestBody ReportRequest reportRequest) throws Exception {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST testReport", currentUser.getTenantId(), currentUser.getId());
        TbReportService svc = tbReportService.orElseThrow(() ->
                new ThingsboardException("Report renderer is not enabled", ThingsboardErrorCode.BAD_REQUEST_PARAMS));
        ReportTask task = buildReportTask(reportRequest);
        ReportData reportData = svc.generateTestReport(task);
        return buildDownloadResponse(reportData.getName(), reportData.getContentType(), reportData.getData());
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @PostMapping("/report/request")
    public Job requestReport(@RequestBody ReportRequest reportRequest) throws Exception {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST requestReport", currentUser.getTenantId(), currentUser.getId());
        ReportTemplateId reportTemplateId = reportRequest.getReportTemplateId();
        if (reportTemplateId == null) {
            throw new IllegalArgumentException("Report template id must be specified");
        }
        checkReportTemplateId(reportTemplateId, Operation.READ);
        UserId userId = resolveUserId(reportRequest);

        ReportJobConfiguration jobConfiguration = ReportJobConfiguration.builder()
                .reportTemplateId(reportTemplateId)
                .userId(userId)
                .timezone(reportRequest.getTimezone())
                .originator(reportRequest.getOriginator())
                .targets(reportRequest.getTargets())
                .notificationTemplateId(reportRequest.getNotificationTemplateId())
                .build();
        // Ad-hoc request, no scheduler event to key off (contrast InferrixSchedulerReportExecutor,
        // which keys on the firing event's id): a fresh UUID key per submission, consistent with
        // how the Job(...) constructor itself mints a fresh configuration.tasksKey below.
        Job job = new Job(getTenantId(), JobType.REPORT, UUID.randomUUID().toString(), reportTemplateId, jobConfiguration);
        return jobManager.submitJob(job).get();
    }

    private PageData<ReportInfo> findReportInfos(UUID reportTemplateId, UUID userId, Boolean includeCustomers,
                                                  int pageSize, int page, String textSearch,
                                                  String sortProperty, String sortOrder) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST findReportInfos pageSize=[{}] page=[{}]", currentUser.getTenantId(), currentUser.getId(), pageSize, page);
        accessControlService.checkPermission(currentUser, Resource.REPORT, Operation.READ);
        TenantId tenantId = currentUser.getTenantId();
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        ReportInfoQuery query = ReportInfoQuery.builder()
                .pageLink(pageLink)
                .reportTemplateId(reportTemplateId != null ? new ReportTemplateId(reportTemplateId) : null)
                .userId(userId != null ? new UserId(userId) : null)
                .includeCustomers(includeCustomers != null && includeCustomers)
                .build();
        return checkNotNull(reportService.findReports(tenantId, query));
    }

    /**
     * Canonical CE idiom for token/owner/{@link ReportTask} construction - mirrors {@code
     * ReportJobProcessor} (lines 90-135) exactly: {@code createUserAccessToken} returns a bare
     * {@code String} (not PE's {@code AccessJwtToken}), so the expiration is pulled back out via
     * {@link JwtTokenFactory#parseTokenClaims}; the owner is resolved as customer-if-any-else-tenant
     * (there is no {@code ownersCacheService} on {@link BaseController}).
     */
    private ReportTask buildReportTask(ReportRequest reportRequest) throws ThingsboardException {
        TenantId tenantId = getTenantId();
        UserId userId = resolveUserId(reportRequest);

        User user = userService.findUserById(tenantId, userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        String accessToken = systemSecurityService.createUserAccessToken(tenantId, userId);
        long accessTokenExpirationTs = jwtTokenFactory.parseTokenClaims(accessToken).getPayload().getExpiration().getTime();

        CustomerId customerId = user.getCustomerId();
        EntityId userOwnerId = (customerId == null || customerId.isNullUid()) ? tenantId : customerId;

        JsonNode configuration = reportRequest.getReportTemplateConfig();
        if (configuration == null) {
            configuration = checkReportTemplateId(reportRequest.getReportTemplateId(), Operation.READ).getConfiguration();
        }

        return ReportTask.builder()
                .tenantId(tenantId)
                .customerId(user.getCustomerId())
                .reportTemplateId(reportRequest.getReportTemplateId())
                .reportTemplateConfig(configuration)
                .timezone(reportRequest.getTimezone())
                .userId(userId)
                .userOwnerId(userOwnerId)
                .originator(reportRequest.getOriginator())
                .accessToken(accessToken)
                .accessTokenExpirationTs(accessTokenExpirationTs)
                .build();
    }

    /**
     * {@link ReportRequest#getUserId()} is a nullable {@code String} by design (PE-parity) that
     * falls back to the calling user when absent - parsed locally rather than typed as
     * {@link UserId} on the DTO itself.
     */
    private UserId resolveUserId(ReportRequest reportRequest) throws ThingsboardException {
        return StringUtils.isNotEmpty(reportRequest.getUserId())
                ? new UserId(UUID.fromString(reportRequest.getUserId()))
                : getCurrentUser().getId();
    }

    private ResponseEntity<ByteArrayResource> buildDownloadResponse(String filename, String contentType, byte[] data) {
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=\"" + filename + "\"")
                .header("x-filename", filename)
                .contentLength(resource.contentLength())
                .header("Content-Type", contentType)
                .body(resource);
    }

}
