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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.scheduler.TbSchedulerService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class SchedulerEventController extends BaseController {

    private static final String SCHEDULER_EVENT_INFO_DESCRIPTION = "Scheduler Events allows you to schedule various types of events with flexible schedule configuration. Scheduler fires configured scheduler events according to their schedule. See the 'Model' tab of the Response Class for more details. ";
    private static final String SCHEDULER_EVENT_WITH_CUSTOMER_INFO_DESCRIPTION = "Scheduler Event With Customer Info extends Scheduler Event Info object and adds 'customerTitle' - a String value representing the title of the customer which user created a Scheduler Event and 'customerIsPublic' - a boolean parameter that specifies if customer is public. See the 'Model' tab of the Response Class for more details. ";
    private static final String SCHEDULER_EVENT_DESCRIPTION = "Scheduler Event extends Scheduler Event Info object and adds 'configuration' - a JSON structure of scheduler event configuration. See the 'Model' tab of the Response Class for more details. ";
    private static final String INVALID_SCHEDULER_EVENT_ID = "Referencing non-existing Scheduler Event Id will cause 'Not Found' error.";

    public static final String SCHEDULER_EVENT_ID = "schedulerEventId";

    private final TbSchedulerService tbSchedulerService;

    @ApiOperation(value = "Get Scheduler Event With Customer Info (getSchedulerEventInfoById)",
            notes = "Fetch the SchedulerEventWithCustomerInfo object based on the provided scheduler event Id. " + SCHEDULER_EVENT_WITH_CUSTOMER_INFO_DESCRIPTION + INVALID_SCHEDULER_EVENT_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvent/info/{schedulerEventId}")
    public SchedulerEventWithCustomerInfo getSchedulerEventInfoById(
            @Parameter(description = "A string value representing the scheduler id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(SCHEDULER_EVENT_ID) String strSchedulerEventId) throws ThingsboardException {
        checkParameter(SCHEDULER_EVENT_ID, strSchedulerEventId);
        SchedulerEventId schedulerEventId = new SchedulerEventId(toUUID(strSchedulerEventId));
        log.debug("[{}][{}] REST getSchedulerEventInfoById [{}]", getTenantId(), getCurrentUser().getId(), schedulerEventId);
        checkSchedulerEventInfoId(schedulerEventId, Operation.READ);
        return schedulerEventService.findSchedulerEventWithCustomerInfoById(getTenantId(), schedulerEventId);
    }

    @ApiOperation(value = "Get Scheduler Event (getSchedulerEventById)",
            notes = "Fetch the SchedulerEvent object based on the provided scheduler event Id. " + SCHEDULER_EVENT_DESCRIPTION + INVALID_SCHEDULER_EVENT_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvent/{schedulerEventId}")
    public SchedulerEvent getSchedulerEventById(
            @Parameter(description = "A string value representing the scheduler id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(SCHEDULER_EVENT_ID) String strSchedulerEventId) throws ThingsboardException {
        checkParameter(SCHEDULER_EVENT_ID, strSchedulerEventId);
        SchedulerEventId schedulerEventId = new SchedulerEventId(toUUID(strSchedulerEventId));
        log.debug("[{}][{}] REST getSchedulerEventById [{}]", getTenantId(), getCurrentUser().getId(), schedulerEventId);
        return checkSchedulerEventId(schedulerEventId, Operation.READ);
    }

    @ApiOperation(value = "Save Scheduler Event (saveSchedulerEvent)",
            notes = "Creates or Updates scheduler event. " + SCHEDULER_EVENT_DESCRIPTION +
                    "When creating scheduler event, platform generates scheduler event Id as time-based UUID. The newly created scheduler event id will be present in the response. " +
                    "Specify existing scheduler event id to update the scheduler event. Referencing non-existing scheduler event Id will cause 'Not Found' error. " +
                    "Remove 'id', 'tenantId' and optionally 'customerId' from the request body example (below) to create new Scheduler Event entity. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/schedulerEvent")
    public SchedulerEvent saveSchedulerEvent(
            @Parameter(description = "A JSON value representing the Scheduler Event.") @RequestBody SchedulerEvent schedulerEvent) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST saveSchedulerEvent [{}]", currentUser.getTenantId(), currentUser.getId(), schedulerEvent.getName());
        schedulerEvent.setTenantId(currentUser.getTenantId());
        if (Authority.CUSTOMER_USER.equals(currentUser.getAuthority())) {
            schedulerEvent.setCustomerId(currentUser.getCustomerId());
        }
        checkEntity(schedulerEvent.getId(), schedulerEvent, Resource.SCHEDULER_EVENT);
        return tbSchedulerService.save(schedulerEvent, currentUser);
    }

    @ApiOperation(value = "Enable or disable Scheduler Event (enableSchedulerEvent)",
            notes = "Updates scheduler event with enabled = true/false. " + SCHEDULER_EVENT_DESCRIPTION +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PutMapping(value = "/schedulerEvent/{schedulerEventId}/enabled/{enabledValue}")
    public SchedulerEvent enableSchedulerEvent(
            @Parameter(description = "A string value representing the scheduler id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(SCHEDULER_EVENT_ID) String strSchedulerEventId,
            @Parameter(description = "Enabled or disabled scheduler", required = true)
            @PathVariable("enabledValue") Boolean enabledValue) throws ThingsboardException {
        checkParameter(SCHEDULER_EVENT_ID, strSchedulerEventId);
        SchedulerEventId schedulerEventId = new SchedulerEventId(toUUID(strSchedulerEventId));
        log.debug("[{}][{}] REST enableSchedulerEvent [{}] enabled=[{}]", getTenantId(), getCurrentUser().getId(), schedulerEventId, enabledValue);
        SchedulerEvent schedulerEvent = checkSchedulerEventId(schedulerEventId, Operation.WRITE);
        schedulerEvent.setEnabled(enabledValue);
        return tbSchedulerService.save(schedulerEvent, getCurrentUser());
    }

    @ApiOperation(value = "Delete Scheduler Event (deleteSchedulerEvent)",
            notes = "Deletes the scheduler event. " + INVALID_SCHEDULER_EVENT_ID +
                    "\n\n Security check is performed to verify that the user has 'DELETE' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @DeleteMapping(value = "/schedulerEvent/{schedulerEventId}")
    @ResponseStatus(HttpStatus.OK)
    public void deleteSchedulerEvent(
            @Parameter(description = "A string value representing the scheduler id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(SCHEDULER_EVENT_ID) String strSchedulerEventId) throws ThingsboardException {
        checkParameter(SCHEDULER_EVENT_ID, strSchedulerEventId);
        SchedulerEventId schedulerEventId = new SchedulerEventId(toUUID(strSchedulerEventId));
        log.debug("[{}][{}] REST deleteSchedulerEvent [{}]", getTenantId(), getCurrentUser().getId(), schedulerEventId);
        SchedulerEvent schedulerEvent = checkSchedulerEventId(schedulerEventId, Operation.DELETE);
        tbSchedulerService.delete(schedulerEvent, getCurrentUser());
    }

    @ApiOperation(value = "Get all scheduler events (getAllSchedulerEvents)",
            notes = "Requested scheduler events must be owned by tenant or assigned to customer which user is performing the request. " + SCHEDULER_EVENT_WITH_CUSTOMER_INFO_DESCRIPTION +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvents")
    public List<SchedulerEventWithCustomerInfo> getAllSchedulerEvents(
            @Parameter(description = "A string value representing the scheduler type. For example, 'generateReport'")
            @RequestParam(required = false) String type) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST getAllSchedulerEvents type=[{}]", currentUser.getTenantId(), currentUser.getId(), type);
        accessControlService.checkPermission(currentUser, Resource.SCHEDULER_EVENT, Operation.READ);
        SchedulerEventFilter filter = SchedulerEventFilter.builder().customerId(currentUser.getCustomerId()).type(type).build();
        return schedulerEventService.findSchedulerEventsByTenantIdAndFilter(currentUser.getTenantId(), filter, new PageLink(1000)).getData();
    }

    @ApiOperation(value = "Get scheduler events (getSchedulerEvents)",
            notes = "Requested scheduler events must be owned by tenant or assigned to customer which user is performing the request. " + SCHEDULER_EVENT_WITH_CUSTOMER_INFO_DESCRIPTION +
                    "\n\nYou can specify parameters to filter the results. The result is wrapped with PageData object that allows you to iterate over result set using pagination. See response schema for more details. " +
                    "\n\n\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvents", params = {"pageSize", "page"})
    public PageData<SchedulerEventWithCustomerInfo> getSchedulerEvents(
            @Parameter(description = "Maximum amount of entities in a one page", required = true) @RequestParam int pageSize,
            @Parameter(description = "Sequence number of page starting from 0", required = true) @RequestParam int page,
            @Parameter(description = "Case-insensitive 'substring' filter based on event's name, type, or customer's name") @RequestParam(required = false) String textSearch,
            @Parameter(description = "Property of entity to sort by") @RequestParam(required = false) String sortProperty,
            @Parameter(description = "Sort order. ASC (ASCENDING) or DESC (DESCENDING)") @RequestParam(required = false) String sortOrder,
            @Parameter(description = "A string value representing the scheduler type. For example, 'generateReport'") @RequestParam(required = false) String type) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST getSchedulerEvents pageSize=[{}] page=[{}] textSearch=[{}] type=[{}]",
                currentUser.getTenantId(), currentUser.getId(), pageSize, page, textSearch, type);
        accessControlService.checkPermission(currentUser, Resource.SCHEDULER_EVENT, Operation.READ);
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        SchedulerEventFilter filter = SchedulerEventFilter.builder().customerId(currentUser.getCustomerId()).type(type).build();
        return schedulerEventService.findSchedulerEventsByTenantIdAndFilter(currentUser.getTenantId(), filter, pageLink);
    }

    @ApiOperation(value = "Get scheduler events (getSchedulerEvents)",
            notes = "Retrieves scheduler events filtering by event run time. Requested scheduler events must be owned by tenant or assigned to customer which user is performing the request. " + SCHEDULER_EVENT_WITH_CUSTOMER_INFO_DESCRIPTION +
                    "\n\nYou can specify parameters to filter the results. The result is wrapped with PageData object that allows you to iterate over result set using pagination. See response schema for more details. " +
                    "\n\n\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvents", params = {"startTime", "endTime"})
    public List<SchedulerEventWithCustomerInfo> getSchedulerEvents(
            @Parameter(description = "A string value representing the scheduler type. For example, 'generateReport'") @RequestParam(required = false) String type,
            @Parameter(description = "Start time filter in milliseconds for scheduler event run time") @RequestParam long startTime,
            @Parameter(description = "End time filter in milliseconds for scheduler event run time") @RequestParam long endTime,
            @Parameter(description = "Case-insensitive 'substring' filter based on event's name, type, or customer's name") @RequestParam(required = false) String textSearch) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST getSchedulerEvents startTime=[{}] endTime=[{}] type=[{}] textSearch=[{}]",
                currentUser.getTenantId(), currentUser.getId(), startTime, endTime, type, textSearch);
        accessControlService.checkPermission(currentUser, Resource.SCHEDULER_EVENT, Operation.READ);
        SchedulerEventTimeFilter filter = SchedulerEventTimeFilter.builder().customerId(currentUser.getCustomerId()).type(type)
                .startTime(startTime).endTime(endTime).build();
        return schedulerEventService.findAllSchedulerEventsByTenantIdAndEventTimeFilter(currentUser.getTenantId(), filter, textSearch);
    }

    @ApiOperation(value = "Get Scheduler Events By Ids (getSchedulerEventsByIds)",
            notes = "Requested scheduler events must be owned by tenant or assigned to customer which user is performing the request. " + SCHEDULER_EVENT_INFO_DESCRIPTION +
                    "\n\nAvailable for users with 'TENANT_ADMIN' or 'CUSTOMER_USER' authority.\n\n Security check is performed to verify that the user has 'READ' permission for the entity (entities).")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/schedulerEvents", params = {"schedulerEventIds"})
    public List<SchedulerEventInfo> getSchedulerEventsByIds(
            @Parameter(description = "A list of scheduler event ids, separated by comma ','", array = @ArraySchema(schema = @Schema(type = "string")), required = true)
            @RequestParam("schedulerEventIds") String[] strSchedulerEventIds) throws ThingsboardException, ExecutionException, InterruptedException {
        checkArrayParameter("schedulerEventIds", strSchedulerEventIds);
        SecurityUser currentUser = getCurrentUser();
        log.debug("[{}][{}] REST getSchedulerEventsByIds count=[{}]", currentUser.getTenantId(), currentUser.getId(), strSchedulerEventIds.length);
        if (!accessControlService.hasPermission(currentUser, Resource.SCHEDULER_EVENT, Operation.READ)) {
            return Collections.emptyList();
        }
        List<SchedulerEventId> schedulerEventIds = new ArrayList<>();
        for (String strSchedulerEventId : strSchedulerEventIds) {
            schedulerEventIds.add(new SchedulerEventId(toUUID(strSchedulerEventId)));
        }
        List<SchedulerEventInfo> schedulerEvents = checkNotNull(
                schedulerEventService.findSchedulerEventInfoByIdsAsync(currentUser.getTenantId(), schedulerEventIds).get());
        return filterSchedulerEventsByReadPermission(schedulerEvents);
    }

    private List<SchedulerEventInfo> filterSchedulerEventsByReadPermission(List<SchedulerEventInfo> schedulerEvents) {
        return schedulerEvents.stream().filter(schedulerEvent -> {
            try {
                return accessControlService.hasPermission(getCurrentUser(), Resource.SCHEDULER_EVENT, Operation.READ,
                        (EntityId) schedulerEvent.getId(), (HasTenantId) schedulerEvent);
            } catch (ThingsboardException e) {
                return false;
            }
        }).toList();
    }

}
