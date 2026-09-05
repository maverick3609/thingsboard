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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.msg.plugin.ComponentLifecycleMsg;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.role.RoleService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
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
        verify(clusterService).broadcastEntityStateChangeEvent(TENANT_ID, USER_ID, ComponentLifecycleEvent.UPDATED);
    }

    @Test
    public void testRoleUpdateEvictsEveryAssigneeAndBroadcastsOnce() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID, otherUserId));

        service.onRoleUpdated(TENANT_ID, ROLE_ID);

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
        // one message for the role, not one per assignee
        verify(clusterService).broadcastEntityStateChangeEvent(TENANT_ID, ROLE_ID, ComponentLifecycleEvent.UPDATED);
        verify(clusterService, never()).broadcastEntityStateChangeEvent(any(), any(UserId.class), any());
    }

    @Test
    public void testIncomingRoleEventEvictsEveryAssignee() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID, otherUserId));

        service.onComponentLifecycleEvent(new ComponentLifecycleMsg(TENANT_ID, ROLE_ID, ComponentLifecycleEvent.UPDATED));

        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
        verifyNoInteractions(clusterService);
    }

    @Test
    public void testBroadcastFailureStillEvictsEveryAssigneeLocally() {
        UserId otherUserId = new UserId(UUID.randomUUID());
        when(roleService.findUserIdsByRoleId(TENANT_ID, ROLE_ID)).thenReturn(List.of(USER_ID, otherUserId));
        doThrow(new RuntimeException("queue is down"))
                .when(clusterService).broadcastEntityStateChangeEvent(any(), any(), any());

        // the DB write is already committed, so a dead queue must not fail the role change
        service.onRoleUpdated(TENANT_ID, ROLE_ID);
        service.onUserRolesUpdated(TENANT_ID, USER_ID);

        verify(cache, times(2)).evict(new UserPermissionCacheKey(TENANT_ID, USER_ID));
        verify(cache).evict(new UserPermissionCacheKey(TENANT_ID, otherUserId));
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

}
