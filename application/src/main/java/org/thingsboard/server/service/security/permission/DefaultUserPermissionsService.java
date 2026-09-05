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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.thingsboard.common.util.JacksonUtil;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
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

    static final String USER_IDS = "userIds";
    static final int EVICTION_BATCH_SIZE = 1000;

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
        evictAndBroadcast(tenantId, roleId, ComponentLifecycleEvent.UPDATED, roleService.findUserIdsByRoleId(tenantId, roleId));
    }

    @Override
    public void onRoleDeleted(TenantId tenantId, RoleId roleId, List<UserId> assignees) {
        evictAndBroadcast(tenantId, roleId, ComponentLifecycleEvent.DELETED, assignees);
    }

    @Override
    public void onUserRolesUpdated(TenantId tenantId, UserId userId) {
        evict(tenantId, userId);
        broadcast(ComponentLifecycleMsg.builder()
                .tenantId(tenantId).entityId(userId).event(ComponentLifecycleEvent.UPDATED).build());
    }

    /**
     * The permissions cache is node-local whenever `cache.type` is caffeine (the default), so a
     * role change on one node would leave every other node enforcing the old permissions until the
     * entry expired. Evict here for an immediate effect on this node, then tell the others to do
     * the same — the shape TB uses for its own per-user security cache
     * (`DefaultUserAuthDetailsCache`). Redis deployments share one cache and only need the evict,
     * but the message is harmless there.
     *
     * <p>The affected users ride along in the message rather than being looked up again on each
     * node: after a delete there is nothing left to look up, and one role edit should not turn
     * into one message per assignee through every node's high-priority actor mailbox. The list is
     * batched so a role held by tens of thousands of users cannot build a message the queue would
     * refuse.
     */
    private void evictAndBroadcast(TenantId tenantId, RoleId roleId, ComponentLifecycleEvent event, List<UserId> userIds) {
        evictAll(tenantId, userIds);
        for (int from = 0; from < userIds.size(); from += EVICTION_BATCH_SIZE) {
            List<UserId> batch = userIds.subList(from, Math.min(from + EVICTION_BATCH_SIZE, userIds.size()));
            broadcast(ComponentLifecycleMsg.builder()
                    .tenantId(tenantId).entityId(roleId).event(event).info(toInfo(batch)).build());
        }
    }

    private static JsonNode toInfo(List<UserId> userIds) {
        ObjectNode info = JacksonUtil.newObjectNode();
        ArrayNode ids = info.putArray(USER_IDS);
        userIds.forEach(userId -> ids.add(userId.getId().toString()));
        return info;
    }

    /**
     * Never fatal: the DB write is already committed by the time we get here, and a queue that
     * refuses the message would otherwise strand the evictions that come after it and report a
     * change that did apply as a failure. A lost message costs the other nodes nothing worse than
     * the cache TTL they had before any of this existed.
     */
    private void broadcast(ComponentLifecycleMsg msg) {
        try {
            clusterService.broadcast(msg);
        } catch (Exception e) {
            log.error("[{}][{}] Failed to broadcast the permissions cache eviction. Other nodes keep " +
                    "the old permissions until the cache entry expires.", msg.getTenantId(), msg.getEntityId(), e);
        }
    }

    /**
     * Receiving half of {@link #broadcast}. Also fires for the user edits TB broadcasts itself,
     * which is why it only evicts and never broadcasts — a listener that re-broadcast would loop
     * forever. It must not throw either: the consumer publishes this event before handing the
     * message to the actor system, so an exception here would swallow the rest of that delivery
     * (the remaining listeners AND `tellWithHighPriority`) for a message that is then committed
     * rather than retried.
     */
    @EventListener(ComponentLifecycleMsg.class)
    public void onComponentLifecycleEvent(ComponentLifecycleMsg event) {
        try {
            EntityId entityId = event.getEntityId();
            if (entityId == null) {
                return;
            }
            if (EntityType.USER.equals(entityId.getEntityType())) {
                evict(event.getTenantId(), new UserId(entityId.getId()));
            } else if (EntityType.ROLE.equals(entityId.getEntityType())) {
                evictCarriedUsers(event, new RoleId(entityId.getId()));
            }
        } catch (Exception e) {
            log.error("Failed to evict the permissions cache for {}", event, e);
        }
    }

    /**
     * Evicts the users the writing node put in the message, one at a time: a single unusable id
     * must cost its own eviction only, not the other 999 in the batch.
     *
     * <p>A message with no ids can only come from a node running an older build during a rolling
     * upgrade, so fall back to the lookup — which still resolves for an update, the case that
     * build could produce.
     */
    private void evictCarriedUsers(ComponentLifecycleMsg event, RoleId roleId) {
        JsonNode info = event.getInfo();
        JsonNode ids = info != null ? info.get(USER_IDS) : null;
        if (ids == null || !ids.isArray()) {
            evictAll(event.getTenantId(), roleService.findUserIdsByRoleId(event.getTenantId(), roleId));
            return;
        }
        for (JsonNode id : ids) {
            try {
                evict(event.getTenantId(), new UserId(UUID.fromString(id.asText())));
            } catch (Exception e) {
                log.error("[{}][{}] Skipping an unusable user id in a permissions cache eviction: {}",
                        event.getTenantId(), roleId, id, e);
            }
        }
    }

    private void evictAll(TenantId tenantId, List<UserId> userIds) {
        for (UserId userId : userIds) {
            evict(tenantId, userId);
        }
    }

    private void evict(TenantId tenantId, UserId userId) {
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
