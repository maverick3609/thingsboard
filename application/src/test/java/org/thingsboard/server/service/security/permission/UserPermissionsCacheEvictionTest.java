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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.util.ProtoUtils;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.msg.plugin.ComponentLifecycleMsg;
import org.thingsboard.server.dao.role.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The permissions cache is node-local under caffeine, so a role change has to reach the other
 * nodes as a cluster message. These cover both halves of that.
 */
public class UserPermissionsCacheEvictionTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final UserId USER_ID = new UserId(UUID.randomUUID());
    private static final RoleId ROLE_ID = new RoleId(UUID.randomUUID());

    private final RoleService roleService = mock(RoleService.class);
    @SuppressWarnings("unchecked")
    private final TbTransactionalCache<UserPermissionCacheKey, MergedUserPermissions> cache = mock(TbTransactionalCache.class);
    private final TbClusterService clusterService = mock(TbClusterService.class);
    private final DefaultUserPermissionsService service =
            new DefaultUserPermissionsService(roleService, cache, clusterService);

    @Test
    public void testUserRolesUpdateEvictsLocallyAndBroadcasts() {
        service.onUserRolesUpdated(TENANT_ID, USER_ID);

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        ComponentLifecycleMsg broadcast = singleBroadcast();
        assertEquals(USER_ID, broadcast.getEntityId());
        assertEquals(ComponentLifecycleEvent.UPDATED, broadcast.getEvent());
    }

    @Test
    public void testRoleUpdateEvictsEveryAssigneeAndBroadcastsOnce() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID, otherUserId));

        service.onRoleUpdated(TENANT_ID, ROLE_ID);

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
        // one message for the whole role, carrying both assignees
        ComponentLifecycleMsg broadcast = singleBroadcast();
        assertEquals(ROLE_ID, broadcast.getEntityId());
        assertEquals(TENANT_ID, broadcast.getTenantId());
        assertEquals(List.of(USER_ID.getId().toString(), otherUserId.getId().toString()), userIdsOf(broadcast));
    }

    @Test
    public void testRoleDeleteBroadcastsOnceForAllAssignees() {
        UserId otherUserId = new UserId(UUID.randomUUID());

        // the assignment rows are already gone, so the caller passes the list it captured
        service.onRoleDeleted(TENANT_ID, ROLE_ID, List.of(USER_ID, otherUserId));

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
        ComponentLifecycleMsg broadcast = singleBroadcast();
        assertEquals(ComponentLifecycleEvent.DELETED, broadcast.getEvent());
        assertEquals(TENANT_ID, broadcast.getTenantId());
        assertEquals(List.of(USER_ID.getId().toString(), otherUserId.getId().toString()), userIdsOf(broadcast));
        // a deleted role cannot be looked up, so nothing may try
        verify(roleService, never()).findUserIdsByRoleId(any(), any());
    }

    @Test
    public void testLargeRoleIsBatchedInsteadOfSentAsOneHugeMessage() {
        List<UserId> assignees = new ArrayList<>();
        for (int i = 0; i < DefaultUserPermissionsService.EVICTION_BATCH_SIZE + 1; i++) {
            assignees.add(new UserId(UUID.randomUUID()));
        }

        service.onRoleDeleted(TENANT_ID, ROLE_ID, assignees);

        ArgumentCaptor<ComponentLifecycleMsg> broadcasts = ArgumentCaptor.forClass(ComponentLifecycleMsg.class);
        verify(clusterService, times(2)).broadcast(broadcasts.capture());
        assertEquals(DefaultUserPermissionsService.EVICTION_BATCH_SIZE, userIdsOf(broadcasts.getAllValues().get(0)).size());
        // every assignee must appear exactly once across the batches - equal sizes prove nothing
        List<String> carried = new ArrayList<>(userIdsOf(broadcasts.getAllValues().get(0)));
        carried.addAll(userIdsOf(broadcasts.getAllValues().get(1)));
        assertEquals(assignees.stream().map(userId -> userId.getId().toString()).collect(Collectors.toList()), carried);
        assignees.forEach(userId -> verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, userId)));
    }

    @Test
    public void testDeletingARoleNobodyHoldsSendsNothing() {
        service.onRoleDeleted(TENANT_ID, ROLE_ID, List.of());

        verifyNoInteractions(clusterService);
        verify(cache, never()).evict(any(UserPermissionCacheKey.class));
    }

    @Test
    public void testIncomingRoleEventEvictsTheUsersItCarries() {
        UserId otherUserId = new UserId(UUID.randomUUID());

        service.onComponentLifecycleEvent(roleMsg(List.of(USER_ID, otherUserId)));

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
        // the ids travelled with the message; no node should re-query for them
        verify(roleService, never()).findUserIdsByRoleId(any(), any());
        verifyNoInteractions(clusterService);
    }

    /**
     * Everything else here hands the listener a message built in this JVM. The ids only reach
     * another node if they survive the proto, and if they did not, every one of those tests would
     * still pass while a role delete evicted nobody anywhere.
     */
    @Test
    public void testTheCarriedIdsSurviveTheProto() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        service.onRoleDeleted(TENANT_ID, ROLE_ID, List.of(USER_ID, otherUserId));
        ComponentLifecycleMsg sent = singleBroadcast();

        ComponentLifecycleMsg received = ProtoUtils.fromProto(ProtoUtils.toProto(sent));

        assertEquals(List.of(USER_ID.getId().toString(), otherUserId.getId().toString()), userIdsOf(received));
        assertEquals(ComponentLifecycleEvent.DELETED, received.getEvent());
        assertEquals(TENANT_ID, received.getTenantId());
        assertEquals(ROLE_ID, received.getEntityId());
    }

    @Test
    public void testIncomingRoleEventWithoutIdsFallsBackToTheLookup() {
        // a node still running the previous build sends no payload
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID));

        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, ROLE_ID, ComponentLifecycleEvent.UPDATED));

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
    }

    @Test
    public void testIncomingRoleEventWithAMalformedPayloadFallsBackToTheLookup() {
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID));

        service.onComponentLifecycleEvent(ComponentLifecycleMsg.builder()
                .tenantId(TENANT_ID).entityId(ROLE_ID).event(ComponentLifecycleEvent.UPDATED)
                .info(JacksonUtil.toJsonNode("{\"userIds\": \"not-an-array\"}")).build());

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
    }

    @Test
    public void testIncomingUserEventEvictsWithoutRebroadcasting() {
        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, USER_ID, ComponentLifecycleEvent.UPDATED));

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        // a listener that re-broadcast would loop forever across the cluster
        verifyNoInteractions(clusterService);
    }

    @Test
    public void testIncomingEventForAnotherEntityTypeIsIgnored() {
        DeviceId deviceId = new DeviceId(UUID.randomUUID());

        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, deviceId, ComponentLifecycleEvent.UPDATED));

        verify(cache, never()).evict(any(UserPermissionCacheKey.class));
        verifyNoInteractions(clusterService);
    }

    @Test
    public void testEventWithoutAnEntityIsIgnored() {
        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, null, ComponentLifecycleEvent.UPDATED));

        verify(cache, never()).evict(any(UserPermissionCacheKey.class));
        verifyNoInteractions(clusterService);
    }

    @Test
    public void testOneUnusableIdDoesNotCostTheOthers() {
        ComponentLifecycleMsg msg = ComponentLifecycleMsg.builder()
                .tenantId(TENANT_ID).entityId(ROLE_ID).event(ComponentLifecycleEvent.UPDATED)
                .info(JacksonUtil.toJsonNode("{\"userIds\": [\"not-a-uuid\", \"" + USER_ID.getId() + "\"]}")).build();

        // the consumer publishes this event before handing the message to the actor system, so an
        // exception here would swallow the rest of that delivery
        service.onComponentLifecycleEvent(msg);

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
    }

    @Test
    public void testBroadcastFailureStillEvictsEveryAssigneeLocally() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID, otherUserId));
        doThrow(new RuntimeException("queue is down")).when(clusterService).broadcast(any());

        // the DB write is already committed, so a dead queue must not fail the role change
        service.onRoleUpdated(TENANT_ID, ROLE_ID);
        service.onUserRolesUpdated(TENANT_ID, USER_ID);

        verify(cache, times(2)).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
    }

    @Test
    public void testAFailedBatchDoesNotStopTheNextOne() {
        List<UserId> assignees = new ArrayList<>();
        for (int i = 0; i < DefaultUserPermissionsService.EVICTION_BATCH_SIZE + 1; i++) {
            assignees.add(new UserId(UUID.randomUUID()));
        }
        doThrow(new RuntimeException("queue is down")).doNothing().when(clusterService).broadcast(any());

        service.onRoleDeleted(TENANT_ID, ROLE_ID, assignees);

        verify(clusterService, times(2)).broadcast(any());
    }

    /**
     * The failure this whole mechanism is most exposed to: an incoming message evicting a key that
     * does not equal the one the cache was populated with silently misses, and every other test
     * here would still pass. Pin the two against each other.
     */
    @Test
    public void testEvictedKeyMatchesTheKeyTheCacheWasPopulatedWith() {
        SecurityUser user = new SecurityUser();
        user.setId(USER_ID);
        user.setTenantId(TENANT_ID);
        user.setAuthority(Authority.TENANT_ADMIN);
        service.getMergedPermissions(user);
        ArgumentCaptor<UserPermissionCacheKey> loaded = ArgumentCaptor.forClass(UserPermissionCacheKey.class);
        verify(cache).getAndPutInTransaction(loaded.capture(), any(), anyBoolean());

        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, USER_ID, ComponentLifecycleEvent.UPDATED));
        ArgumentCaptor<UserPermissionCacheKey> evicted = ArgumentCaptor.forClass(UserPermissionCacheKey.class);
        verify(cache).evict(evicted.capture());

        assertEquals(loaded.getValue(), evicted.getValue());
    }

    private ComponentLifecycleMsg singleBroadcast() {
        ArgumentCaptor<ComponentLifecycleMsg> broadcast = ArgumentCaptor.forClass(ComponentLifecycleMsg.class);
        verify(clusterService).broadcast(broadcast.capture());
        return broadcast.getValue();
    }

    private static List<String> userIdsOf(ComponentLifecycleMsg msg) {
        JsonNode ids = msg.getInfo().get(DefaultUserPermissionsService.USER_IDS);
        assertTrue(ids.isArray(), "the message must carry the affected users");
        List<String> userIds = new ArrayList<>();
        ids.forEach(id -> userIds.add(id.asText()));
        return userIds;
    }

    private static ComponentLifecycleMsg roleMsg(List<UserId> userIds) {
        return ComponentLifecycleMsg.builder()
                .tenantId(TENANT_ID).entityId(ROLE_ID).event(ComponentLifecycleEvent.UPDATED)
                .info(JacksonUtil.toJsonNode("{\"userIds\": [" + userIds.stream()
                        .map(userId -> "\"" + userId.getId() + "\"").collect(Collectors.joining(",")) + "]}"))
                .build();
    }

}
