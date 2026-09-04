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
package org.thingsboard.server.service.security.permission;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.cache.TbCacheValueWrapper;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.role.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultUserPermissionsService implements UserPermissionsService {

    private final RoleService roleService;
    private final TbTransactionalCache<UserPermissionCacheKey, MergedUserPermissions> cache;

    @Override
    public MergedUserPermissions getMergedPermissions(SecurityUser user) {
        UserPermissionCacheKey key = new UserPermissionCacheKey(user.getTenantId(), user.getId());
        TbCacheValueWrapper<MergedUserPermissions> cached = cache.get(key);
        if (cached != null) {
            return cached.get();
        }
        List<Role> roles = roleService.findRolesByUserId(user.getTenantId(), user.getId());
        MergedUserPermissions result = mergeRolePermissions(roles);
        // null (no roles => legacy authority-based access) is cached too — the wrapper
        // distinguishes "cached null" from "not cached".
        cache.put(key, result);
        return result;
    }

    @Override
    public void onRoleUpdated(TenantId tenantId, RoleId roleId) {
        for (UserId userId : roleService.findUserIdsByRoleId(tenantId, roleId)) {
            cache.evict(new UserPermissionCacheKey(tenantId, userId));
        }
    }

    @Override
    public void onUserRolesUpdated(TenantId tenantId, UserId userId) {
        cache.evict(new UserPermissionCacheKey(tenantId, userId));
    }

    /**
     * Union of the PE-shaped role permission JSONs. Unknown resource/operation names are skipped
     * with a warning instead of failing the request — save-time validation
     * (DefaultTbRoleService) is the strict gate; this keeps auth working if a bad JSON ever
     * lands in the DB (e.g. after an upstream enum rename).
     */
    static MergedUserPermissions mergeRolePermissions(List<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        Map<Resource, Set<Operation>> generic = new EnumMap<>(Resource.class);
        for (Role role : roles) {
            JsonNode permissions = role.getPermissions();
            if (permissions == null || !permissions.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> entry : permissions.properties()) {
                Resource resource;
                try {
                    resource = Resource.valueOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    log.warn("[{}] Skipping unknown resource [{}] in role permissions", role.getId(), entry.getKey());
                    continue;
                }
                if (!entry.getValue().isArray()) {
                    continue;
                }
                Set<Operation> operations = generic.computeIfAbsent(resource, r -> EnumSet.noneOf(Operation.class));
                for (JsonNode operationNode : entry.getValue()) {
                    try {
                        operations.add(Operation.valueOf(operationNode.asText()));
                    } catch (IllegalArgumentException e) {
                        log.warn("[{}] Skipping unknown operation [{}] in role permissions", role.getId(), operationNode.asText());
                    }
                }
            }
        }
        return new MergedUserPermissions(generic);
    }

}
