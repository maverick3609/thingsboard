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
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.entity.EntityDaoService;

import java.util.List;
import java.util.Optional;

public interface RoleService extends EntityDaoService {

    Role saveRole(Role role);

    Role findRoleById(TenantId tenantId, RoleId roleId);

    List<Role> findRolesByIds(TenantId tenantId, List<RoleId> roleIds);

    PageData<Role> findRolesByTenantId(TenantId tenantId, PageLink pageLink);

    Optional<Role> findRoleByTenantIdAndName(TenantId tenantId, String name);

    void deleteRole(TenantId tenantId, RoleId roleId);

    List<Role> findRolesByUserId(TenantId tenantId, UserId userId);

    List<UserId> findUserIdsByRoleId(TenantId tenantId, RoleId roleId);

    void updateUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds);

}
