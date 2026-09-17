// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.role;

import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;

import java.util.List;

/**
 * user_role join table access (RBAC Option B: roles are assigned directly to users).
 * Rows are removed by FK ON DELETE CASCADE when either the user or the role is deleted.
 */
public interface UserRoleDao {

    List<UserId> findUserIdsByRoleId(RoleId roleId);

    void replaceUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds);

}
