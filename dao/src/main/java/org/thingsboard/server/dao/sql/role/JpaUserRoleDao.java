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
    public List<RoleId> findRoleIdsByUserId(UserId userId) {
        return userRoleRepository.findAllByUserId(userId.getId()).stream()
                .map(entity -> new RoleId(entity.getRoleId())).toList();
    }

    @Override
    public List<UserId> findUserIdsByRoleId(RoleId roleId) {
        return userRoleRepository.findAllByRoleId(roleId.getId()).stream()
                .map(entity -> new UserId(entity.getUserId())).toList();
    }

    @Override
    @Transactional
    public void replaceUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds) {
        userRoleRepository.deleteAllByUserId(userId.getId());
        if (roleIds != null && !roleIds.isEmpty()) {
            long ts = System.currentTimeMillis();
            List<UserRoleEntity> rows = roleIds.stream().distinct()
                    .map(roleId -> new UserRoleEntity(userId.getId(), roleId.getId(), tenantId.getId(), ts))
                    .toList();
            userRoleRepository.saveAll(rows);
        }
    }

}
