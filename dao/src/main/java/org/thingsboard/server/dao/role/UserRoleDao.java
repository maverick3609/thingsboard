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

    List<RoleId> findRoleIdsByUserId(UserId userId);

    List<UserId> findUserIdsByRoleId(RoleId roleId);

    void replaceUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds);

}
