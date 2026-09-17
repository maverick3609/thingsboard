// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.role;

import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.Dao;
import org.thingsboard.server.dao.TenantEntityDao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleDao extends Dao<Role>, TenantEntityDao<Role> {

    /**
     * Takes a FOR UPDATE lock on the role row and returns whether it exists in that tenant;
     * see RoleRepository#lockRole.
     */
    boolean lockRole(UUID tenantId, UUID roleId);

    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);

    List<Role> findRolesByIds(UUID tenantId, List<UUID> roleIds);

    List<Role> findRolesByUserId(UUID tenantId, UUID userId);

}
