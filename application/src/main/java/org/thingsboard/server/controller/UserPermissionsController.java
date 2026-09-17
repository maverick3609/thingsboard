// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AllowedPermissionsInfo;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.Arrays;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserPermissionsController extends BaseController {

    @ApiOperation(value = "Get Allowed Permissions (getAllowedPermissions)",
            notes = "Returns the merged role permissions of the current user (null when the user has no roles and " +
                    "keeps the default authority-based access) together with the resource/operation vocabularies " +
                    "used by the role editor and for enabling/disabling UI components.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/permissions/allowedPermissions")
    public AllowedPermissionsInfo getAllowedPermissions() throws ThingsboardException {
        SecurityUser currentUser = getCurrentUser();
        return new AllowedPermissionsInfo(currentUser.getUserPermissions(),
                Arrays.asList(Resource.values()), Arrays.asList(Operation.values()));
    }

}
