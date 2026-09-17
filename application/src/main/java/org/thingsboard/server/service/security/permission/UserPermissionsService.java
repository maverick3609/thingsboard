// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;

public interface UserPermissionsService {

    /**
     * Returns the merged permissions of all roles assigned to the user, or {@code null} when the
     * user has no roles — a role-less user keeps the legacy authority-based access
     * (see {@link UserPermissionsUtil#granted}).
     */
    MergedUserPermissions getMergedPermissions(SecurityUser user);

    void onRoleUpdated(TenantId tenantId, RoleId roleId);

    /**
     * Same as {@link #onRoleUpdated} for a role that no longer exists: the assignees have to be
     * captured before the delete, because the assignment rows go with it. The capture is not
     * serialised against a concurrent assignment, so a user assigned the role while it was being
     * deleted may be missing from the list and keep the now-deleted role's permissions until the
     * cache entry expires.
     */
    void onRoleDeleted(TenantId tenantId, RoleId roleId, List<UserId> assignees);

    void onUserRolesUpdated(TenantId tenantId, UserId userId);

}
