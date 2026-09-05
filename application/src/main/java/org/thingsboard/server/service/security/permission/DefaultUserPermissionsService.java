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
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.stereotype.Service;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.msg.plugin.ComponentLifecycleMsg;
import org.thingsboard.server.dao.role.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultUserPermissionsService implements UserPermissionsService {

    private final RoleService roleService;
    private final TbTransactionalCache<UserPermissionCacheKey, MergedUserPermissions> cache;
    @Lazy
    private final TbClusterService clusterService;
    private final AtomicBoolean schemaMissingReported = new AtomicBoolean();

    @Override
    public MergedUserPermissions getMergedPermissions(SecurityUser user) {
        UserPermissionCacheKey key = new UserPermissionCacheKey(user.getTenantId(), user.getId());
        // Transactional get-or-load: a concurrent evict (role edited / unassigned while this
        // request is reading the DB) fails the put, so a revocation can never be overwritten by
        // an in-flight stale read. cacheNullValue=true keeps "no roles => legacy access" cached.
        return cache.getAndPutInTransaction(key, () -> loadPermissions(user), true);
    }

    private MergedUserPermissions loadPermissions(SecurityUser user) {
        try {
            return mergeRolePermissions(roleService.findRolesByUserId(user.getTenantId(), user.getId()));
        } catch (InvalidDataAccessResourceUsageException e) {
            // role / user_role are missing: the RBAC DDL was never applied on this install (jar
            // swapped without an upgrade run). Every authenticated request would otherwise fail
            // authentication and lock the whole tenant out, so fall back to legacy access.
            if (schemaMissingReported.compareAndSet(false, true)) {
                log.error("RBAC tables are missing - roles are not enforced until the database upgrade is run. " +
                        "Apply dao/src/main/resources/sql/schema-inferrix.sql or run the upgrade.", e);
            }
            return null;
        }
    }

    @Override
    public void onRoleUpdated(TenantId tenantId, RoleId roleId) {
        evictAssignees(tenantId, roleId);
        // one ROLE message for the whole role: every node expands it with its own assignee lookup,
        // so a role held by hundreds of users does not turn into hundreds of cluster messages
        broadcast(tenantId, roleId);
    }

    @Override
    public void onUserRolesUpdated(TenantId tenantId, UserId userId) {
        cache.evict(new UserPermissionCacheKey(tenantId, userId));
        broadcast(tenantId, userId);
    }

    /**
     * The permissions cache is node-local whenever `cache.type` is caffeine (the default), so a
     * role change on one node would leave every other node enforcing the old permissions until the
     * entry expired. The callers evict locally for an immediate effect on this node, then this
     * tells the others to do the same — the shape TB uses for its own per-user security cache
     * (`DefaultUserAuthDetailsCache`). Redis deployments share one cache and only need the local
     * evict, but the message is harmless there.
     *
     * <p>Never fatal: the DB write is already committed by the time we get here, and a queue that
     * refuses the message would otherwise strand the evictions that come after it and report a
     * change that did apply as a failure. A lost message costs the other nodes nothing worse than
     * the cache TTL they had before any of this existed.
     */
    private void broadcast(TenantId tenantId, EntityId entityId) {
        try {
            clusterService.broadcastEntityStateChangeEvent(tenantId, entityId, ComponentLifecycleEvent.UPDATED);
        } catch (Exception e) {
            log.error("[{}][{}] Failed to broadcast the permissions cache eviction. Other nodes keep " +
                    "the old permissions until the cache entry expires.", tenantId, entityId, e);
        }
    }

    private void evictAssignees(TenantId tenantId, RoleId roleId) {
        for (UserId userId : roleService.findUserIdsByRoleId(tenantId, roleId)) {
            cache.evict(new UserPermissionCacheKey(tenantId, userId));
        }
    }

    /**
     * Receiving half of {@link #broadcast}. Also fires for the user edits TB broadcasts itself,
     * which is why it only evicts and never broadcasts — a listener that re-broadcast would loop
     * forever. A role delete arrives as one message per assignee instead, since by the time it
     * lands the assignment rows are gone and the lookup here would find nobody.
     */
    @EventListener(ComponentLifecycleMsg.class)
    public void onComponentLifecycleEvent(ComponentLifecycleMsg event) {
        EntityId entityId = event.getEntityId();
        if (entityId == null) {
            return;
        }
        if (EntityType.USER.equals(entityId.getEntityType())) {
            cache.evict(new UserPermissionCacheKey(event.getTenantId(), new UserId(entityId.getId())));
        } else if (EntityType.ROLE.equals(entityId.getEntityType())) {
            evictAssignees(event.getTenantId(), new RoleId(entityId.getId()));
        }
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
