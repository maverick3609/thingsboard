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

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.report.TbReportTemplateService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.List;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ReportTemplateController extends BaseController {

    private static final String REPORT_TEMPLATE_INFO_DESCRIPTION = "Report Templates allows you to create reports according to the report template configuration. Report service uses report template configuration to generate report. See the 'Model' tab of the Response Class for more details. ";
    private static final String REPORT_TEMPLATE_DESCRIPTION = "Report Template extends Report Template Info object and adds 'configuration' - a JSON structure of report template configuration. See the 'Model' tab of the Response Class for more details. ";
    private static final String REPORT_TEMPLATE_QUERY_TYPE_ARRAY_DESCRIPTION = "A list of string values separated by comma ',' representing one of the ReportTemplateType enumeration value.";
    private static final String REPORT_TEMPLATE_QUERY_FORMAT_ARRAY_DESCRIPTION = "A list of string values separated by comma ',' representing one of the TbReportFormat enumeration value.";
    private static final String INVALID_REPORT_TEMPLATE_ID = "Referencing non-existing Report Template Id will cause 'Not Found' error.";

    public static final String REPORT_TEMPLATE_ID = "reportTemplateId";

    private final TbReportTemplateService tbReportTemplateService;

    @ApiOperation(value = "Get Report Template (getReportTemplateById)",
            notes = "Fetch the ReportTemplate object based on the provided report template Id. " + REPORT_TEMPLATE_DESCRIPTION + INVALID_REPORT_TEMPLATE_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/reportTemplate/{reportTemplateId}")
    public ReportTemplate getReportTemplateById(
            @Parameter(description = "A string value representing the report template id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(REPORT_TEMPLATE_ID) String strReportTemplateId) throws ThingsboardException {
        checkParameter(REPORT_TEMPLATE_ID, strReportTemplateId);
        ReportTemplateId reportTemplateId = new ReportTemplateId(toUUID(strReportTemplateId));
        log.debug("[{}][{}] REST getReportTemplateById [{}]", getTenantId(), getCurrentUser().getId(), reportTemplateId);
        return checkReportTemplateId(reportTemplateId, Operation.READ);
    }

    @ApiOperation(value = "Get Report Template Info (getReportTemplateInfoById)",
            notes = "Fetch the ReportTemplateInfo object based on the provided report template Id. " + REPORT_TEMPLATE_INFO_DESCRIPTION + INVALID_REPORT_TEMPLATE_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/reportTemplate/info/{reportTemplateId}")
    public ReportTemplateInfo getReportTemplateInfoById(
            @Parameter(description = "A string value representing the report template id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(REPORT_TEMPLATE_ID) String strReportTemplateId) throws ThingsboardException {
        checkParameter(REPORT_TEMPLATE_ID, strReportTemplateId);
        ReportTemplateId reportTemplateId = new ReportTemplateId(toUUID(strReportTemplateId));
        log.debug("[{}][{}] REST getReportTemplateInfoById [{}]", getTenantId(), getCurrentUser().getId(), reportTemplateId);
        // BaseController only exposes checkReportTemplateId (Task 18) — there is no
        // checkReportTemplateInfoId helper (unlike checkSchedulerEventInfoId for SchedulerEvent).
        // Reuse the same protected checkEntityId(I, ThrowingBiFunction, Operation) dispatcher that
        // those generated helpers delegate to, pointed at the Info-projection dao method directly,
        // rather than adding a new method to the shared BaseController for a single call site.
        return checkEntityId(reportTemplateId, reportTemplateService::findReportTemplateInfoById, Operation.READ);
    }

    @ApiOperation(value = "Save Report Template (saveReportTemplate)",
            notes = "Creates or Updates report template. " + REPORT_TEMPLATE_DESCRIPTION +
                    "When creating report template, platform generates report template Id as time-based UUID. The newly created report template id will be present in the response. " +
                    "Specify existing report template id to update the report template. Referencing non-existing report template Id will cause 'Not Found' error. " +
                    "Remove 'id', 'tenantId' and optionally 'customerId' from the request body example (below) to create new Report Template entity. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/reportTemplate")
    public ReportTemplate saveReportTemplate(
            @Parameter(description = "A JSON value representing the Report Template.") @RequestBody ReportTemplate reportTemplate) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST saveReportTemplate [{}]", currentUser.getTenantId(), currentUser.getId(), reportTemplate.getName());
        reportTemplate.setTenantId(currentUser.getTenantId());
        checkEntity(reportTemplate.getId(), reportTemplate, Resource.REPORT_TEMPLATE);
        return tbReportTemplateService.save(reportTemplate, currentUser);
    }

    @ApiOperation(value = "Delete Report Template (deleteReportTemplate)",
            notes = "Deletes the report template. " + INVALID_REPORT_TEMPLATE_ID +
                    "\n\n Security check is performed to verify that the user has 'DELETE' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/reportTemplate/{reportTemplateId}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteReportTemplate(
            @Parameter(description = "A string value representing the report template id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(REPORT_TEMPLATE_ID) String strReportTemplateId) throws ThingsboardException {
        checkParameter(REPORT_TEMPLATE_ID, strReportTemplateId);
        ReportTemplateId reportTemplateId = new ReportTemplateId(toUUID(strReportTemplateId));
        log.debug("[{}][{}] REST deleteReportTemplate [{}]", getTenantId(), getCurrentUser().getId(), reportTemplateId);
        ReportTemplate reportTemplate = checkReportTemplateId(reportTemplateId, Operation.DELETE);
        tbReportTemplateService.delete(reportTemplate, getCurrentUser());
    }

    @ApiOperation(value = "Get All Report Templates for current user (getAllReportTemplateInfos)",
            notes = "Returns a page of report template info objects owned by the tenant of a current user. " + REPORT_TEMPLATE_INFO_DESCRIPTION +
                    "You can specify parameters to filter the results. The result is wrapped with PageData object that allows you to iterate over result set using pagination. See response schema for more details. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority. Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/reportTemplateInfos/all", params = {"pageSize", "page"})
    public PageData<ReportTemplateInfo> getAllReportTemplateInfos(
            @Parameter(description = REPORT_TEMPLATE_QUERY_TYPE_ARRAY_DESCRIPTION, array = @ArraySchema(schema = @Schema(type = "string", allowableValues = {"REPORT", "SUB_REPORT"})))
            @RequestParam(required = false) String[] typeList,
            @Parameter(description = REPORT_TEMPLATE_QUERY_FORMAT_ARRAY_DESCRIPTION, array = @ArraySchema(schema = @Schema(type = "string", allowableValues = {"PDF", "CSV"})))
            @RequestParam(required = false) String[] formatList,
            @Parameter(description = "Include customer or sub-customer entities") @RequestParam(required = false) Boolean includeCustomers,
            @Parameter(description = "Maximum amount of entities in a one page", required = true) @RequestParam int pageSize,
            @Parameter(description = "Sequence number of page starting from 0", required = true) @RequestParam int page,
            @Parameter(description = "The case insensitive 'substring' filter based on the report template name or customer title.") @RequestParam(required = false) String textSearch,
            @Parameter(description = "Property of entity to sort by", schema = @Schema(allowableValues = {"createdTime", "name", "ownerName"})) @RequestParam(required = false) String sortProperty,
            @Parameter(description = "Sort order. ASC (ASCENDING) or DESC (DESCENDING)", schema = @Schema(allowableValues = {"ASC", "DESC"})) @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST getAllReportTemplateInfos pageSize=[{}] page=[{}]", currentUser.getTenantId(), currentUser.getId(), pageSize, page);
        accessControlService.checkPermission(currentUser, Resource.REPORT_TEMPLATE, Operation.READ);
        TenantId tenantId = currentUser.getTenantId();
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        List<ReportTemplateType> reportTemplateTypeList = new ArrayList<>();
        if (typeList != null) {
            for (String strType : typeList) {
                if (StringUtils.isEmpty(strType)) {
                    continue;
                }
                reportTemplateTypeList.add(ReportTemplateType.valueOf(strType));
            }
        }
        List<TbReportFormat> reportTemplateFormatList = new ArrayList<>();
        if (formatList != null) {
            for (String strFormat : formatList) {
                if (StringUtils.isEmpty(strFormat)) {
                    continue;
                }
                reportTemplateFormatList.add(TbReportFormat.valueOf(strFormat));
            }
        }
        ReportTemplateQuery query = ReportTemplateQuery.builder().pageLink(pageLink)
                .includeCustomers(includeCustomers != null && includeCustomers)
                .formatList(reportTemplateFormatList).typeList(reportTemplateTypeList).build();
        return checkNotNull(reportTemplateService.findReportTemplateInfos(tenantId, query));
    }

    @ApiOperation(value = "Get report templates by Report Template Ids (getReportTemplatesByIds)",
            notes = "Returns a list of ReportTemplate objects based on the provided ids. Filters the list based on the user permissions. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority. Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/reportTemplates", params = {"reportTemplateIds"})
    public List<ReportTemplate> getReportTemplatesByIds(
            @Parameter(description = "A list of report template ids, separated by comma ','", array = @ArraySchema(schema = @Schema(type = "string")), required = true)
            @RequestParam("reportTemplateIds") String[] strReportTemplateIds) throws ThingsboardException {
        checkArrayParameter("reportTemplateIds", strReportTemplateIds);
        SecurityUser currentUser = getCurrentUser();
        TenantId tenantId = currentUser.getTenantId();
        log.debug("[{}][{}] REST getReportTemplatesByIds count=[{}]", tenantId, currentUser.getId(), strReportTemplateIds.length);
        List<ReportTemplateId> reportTemplateIds = new ArrayList<>();
        for (String strReportTemplateId : strReportTemplateIds) {
            reportTemplateIds.add(new ReportTemplateId(toUUID(strReportTemplateId)));
        }
        List<ReportTemplate> reportTemplates = checkNotNull(reportTemplateService.findReportTemplatesByIds(tenantId, reportTemplateIds));
        return filterReportTemplatesByReadPermission(reportTemplates);
    }

    private List<ReportTemplate> filterReportTemplatesByReadPermission(List<ReportTemplate> reportTemplates) {
        return reportTemplates.stream().filter(reportTemplate -> {
            try {
                return accessControlService.hasPermission(getCurrentUser(), Resource.REPORT_TEMPLATE, Operation.READ,
                        reportTemplate.getId(), reportTemplate);
            } catch (ThingsboardException e) {
                return false;
            }
        }).toList();
    }

}
