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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.role.TbRoleService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.List;

import static org.thingsboard.server.controller.ControllerConstants.PAGE_DATA_PARAMETERS;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;

/**
 * RBAC port (Option B): PE-compatible role CRUD paths plus Inferrix-specific direct
 * user ↔ role assignment endpoints (PE assigns roles to user groups, which CE does not have).
 */
@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class RoleController extends BaseController {

    private static final String ROLE_DESCRIPTION = "The Role represents a named set of permissions: a JSON object where each key is a resource " +
            "(DEVICE, ASSET, DASHBOARD, ALL, ...) and the value is an array of allowed operations (READ, WRITE, DELETE, ALL, ...). " +
            "Users with at least one assigned role are restricted to the union of their roles' permissions; users without roles keep the default authority-based access. ";
    private static final String INVALID_ROLE_ID = "Referencing non-existing Role Id will cause 'Not Found' error.";

    private static final String ROLE_TEXT_SEARCH_DESCRIPTION = "The case insensitive 'substring' filter based on the role name.";

    public static final String ROLE_ID = "roleId";
    public static final String USER_ID = "userId";

    private final TbRoleService tbRoleService;

    @ApiOperation(value = "Get Role (getRoleById)",
            notes = "Fetch the Role object based on the provided role Id. " + ROLE_DESCRIPTION + INVALID_ROLE_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/role/{roleId}")
    public Role getRoleById(
            @Parameter(description = "A string value representing the role id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(ROLE_ID) String strRoleId) throws ThingsboardException {
        checkParameter(ROLE_ID, strRoleId);
        RoleId roleId = new RoleId(toUUID(strRoleId));
        return checkRoleId(roleId, Operation.READ);
    }

    @ApiOperation(value = "Save Role (saveRole)",
            notes = "Creates or Updates the Role. " + ROLE_DESCRIPTION +
                    "When creating a role, platform generates Role Id as time-based UUID. " +
                    "Specify existing Role Id to update the role. " + INVALID_ROLE_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/role")
    public Role saveRole(
            @Parameter(description = "A JSON value representing the role.", required = true) @RequestBody Role role) throws Exception {
        SecurityUser currentUser = getCurrentUser();
        role.setTenantId(currentUser.getTenantId());
        checkEntity(role.getId(), role, Resource.ROLE);
        return tbRoleService.save(role, currentUser);
    }

    @ApiOperation(value = "Delete Role (deleteRole)",
            notes = "Deletes the role and removes it from all users it was assigned to. " + INVALID_ROLE_ID +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/role/{roleId}")
    public void deleteRole(
            @Parameter(description = "A string value representing the role id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(ROLE_ID) String strRoleId) throws ThingsboardException {
        checkParameter(ROLE_ID, strRoleId);
        RoleId roleId = new RoleId(toUUID(strRoleId));
        Role role = checkRoleId(roleId, Operation.DELETE);
        tbRoleService.delete(role, getCurrentUser());
    }

    @ApiOperation(value = "Get Roles (getRoles)",
            notes = "Returns a page of roles owned by tenant. " + PAGE_DATA_PARAMETERS +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/roles", params = {"pageSize", "page"})
    public PageData<Role> getRoles(
            @Parameter(description = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @Parameter(description = ROLE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION, schema = @Schema(allowableValues = {"createdTime", "name", "type"}))
            @RequestParam(required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION, schema = @Schema(allowableValues = {"ASC", "DESC"}))
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        accessControlService.checkPermission(currentUser, Resource.ROLE, Operation.READ);
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        return checkNotNull(roleService.findRolesByTenantId(currentUser.getTenantId(), pageLink));
    }

    @ApiOperation(value = "Get Roles By Ids (getRolesByIds)",
            notes = "Requested roles must be owned by tenant which is performing the request. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/roles", params = {"roleIds"})
    public List<Role> getRolesByIds(
            @Parameter(description = "A list of role ids, separated by comma ','", array = @ArraySchema(schema = @Schema(type = "string")), required = true)
            @RequestParam("roleIds") String[] strRoleIds) throws ThingsboardException {
        checkArrayParameter("roleIds", strRoleIds);
        SecurityUser currentUser = getCurrentUser();
        accessControlService.checkPermission(currentUser, Resource.ROLE, Operation.READ);
        List<RoleId> roleIds = new ArrayList<>();
        for (String strRoleId : strRoleIds) {
            roleIds.add(new RoleId(toUUID(strRoleId)));
        }
        return checkNotNull(roleService.findRolesByIds(currentUser.getTenantId(), roleIds));
    }

    @ApiOperation(value = "Get User Roles (getUserRoles)",
            notes = "Returns the list of roles assigned to the user. An empty list means the user keeps the default authority-based access. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/user/{userId}/roles")
    public List<Role> getUserRoles(
            @Parameter(description = "A string value representing the user id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(USER_ID) String strUserId) throws ThingsboardException {
        checkParameter(USER_ID, strUserId);
        accessControlService.checkPermission(getCurrentUser(), Resource.ROLE, Operation.READ);
        UserId userId = new UserId(toUUID(strUserId));
        checkUserId(userId, Operation.READ);
        return checkNotNull(roleService.findRolesByUserId(getTenantId(), userId));
    }

    @ApiOperation(value = "Update User Roles (updateUserRoles)",
            notes = "Replaces the set of roles assigned to the user with the provided list of role ids. " +
                    "An empty list removes all roles and restores the default authority-based access. " +
                    "\n\nAvailable for users with 'TENANT_ADMIN' authority.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/user/{userId}/roles")
    public void updateUserRoles(
            @Parameter(description = "A string value representing the user id. For example, '784f394c-42b6-435a-983c-b7beff2784f9'", required = true)
            @PathVariable(USER_ID) String strUserId,
            @Parameter(description = "A JSON array of role ids to assign to the user.", required = true)
            @RequestBody String[] strRoleIds) throws ThingsboardException {
        checkParameter(USER_ID, strUserId);
        // assignment is the privilege-granting operation: require ROLE WRITE on top of USER WRITE,
        // so a role-restricted admin without ROLE grants cannot strip restrictions off users
        accessControlService.checkPermission(getCurrentUser(), Resource.ROLE, Operation.WRITE);
        UserId userId = new UserId(toUUID(strUserId));
        User targetUser = checkUserId(userId, Operation.WRITE);
        List<RoleId> roleIds = new ArrayList<>();
        if (strRoleIds != null) {
            for (String strRoleId : strRoleIds) {
                roleIds.add(new RoleId(toUUID(strRoleId)));
            }
        }
        tbRoleService.updateUserRoles(targetUser, roleIds, getCurrentUser());
    }

}
