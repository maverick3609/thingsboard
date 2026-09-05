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
package org.thingsboard.server.dao.sql.role;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.dao.model.sql.UserRoleEntity;
import org.thingsboard.server.dao.role.UserRoleDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.List;

@Component
@SqlDao
@RequiredArgsConstructor
public class JpaUserRoleDao implements UserRoleDao {

    private final UserRoleRepository userRoleRepository;

    @Override
    public List<UserId> findUserIdsByRoleId(RoleId roleId) {
        // ids only: this runs while the role row is locked, so it must not hydrate a row per
        // assignment for a role held by thousands of users
        return userRoleRepository.findUserIdsByRoleId(roleId.getId()).stream().map(UserId::new).toList();
    }

    @Override
    @Transactional
    public void replaceUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds) {
        userRoleRepository.lockUser(userId.getId());
        List<RoleId> distinctRoleIds = roleIds == null ? List.of() : roleIds.stream().distinct().toList();
        if (!distinctRoleIds.isEmpty()) {
            // before touching user_role, and in id order: a role delete takes the role row first
            // and the assignment rows second, so this path must do the same or the two deadlock
            // over each other's rows. Sorting keeps two concurrent assignments from inverting.
            userRoleRepository.lockRoles(distinctRoleIds.stream().map(RoleId::getId).sorted().toList());
        }
        userRoleRepository.deleteAllByUserId(userId.getId());
        if (!distinctRoleIds.isEmpty()) {
            long ts = System.currentTimeMillis();
            List<UserRoleEntity> rows = distinctRoleIds.stream()
                    .map(roleId -> new UserRoleEntity(userId.getId(), roleId.getId(), tenantId.getId(), ts))
                    .toList();
            userRoleRepository.saveAll(rows);
        }
    }

}
