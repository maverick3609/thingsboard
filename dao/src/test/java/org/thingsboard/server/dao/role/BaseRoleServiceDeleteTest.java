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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.entity.EntityCountService;
import org.thingsboard.server.dao.sql.role.RoleRepository;
import org.thingsboard.server.dao.sql.role.UserRoleRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The assignee list a role delete hands to the permissions cache is only complete if it is read
 * under a lock on the role — otherwise an assignment committing between the read and the cascade
 * is deleted without anyone evicting that user.
 */
public class BaseRoleServiceDeleteTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final RoleId ROLE_ID = new RoleId(UUID.randomUUID());
    private static final UserId USER_ID = new UserId(UUID.randomUUID());

    private final RoleDao roleDao = mock(RoleDao.class);
    private final UserRoleDao userRoleDao = mock(UserRoleDao.class);
    private final BaseRoleService service = new BaseRoleService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "roleDao", roleDao);
        ReflectionTestUtils.setField(service, "userRoleDao", userRoleDao);
        ReflectionTestUtils.setField(service, "entityCountService", mock(EntityCountService.class));
        ReflectionTestUtils.setField(service, "eventPublisher", mock(ApplicationEventPublisher.class));

        Role role = new Role();
        role.setId(ROLE_ID);
        role.setTenantId(TENANT_ID);
        when(roleDao.findById(TENANT_ID, ROLE_ID.getId())).thenReturn(role);
        when(userRoleDao.findUserIdsByRoleId(ROLE_ID)).thenReturn(List.of(USER_ID));
    }

    @Test
    public void testAssigneesAreReadUnderTheLockAndBeforeTheDelete() {
        when(roleDao.lockRole(TENANT_ID.getId(), ROLE_ID.getId())).thenReturn(true);

        List<UserId> assignees = service.deleteRoleAndCollectAssignees(TENANT_ID, ROLE_ID);

        assertEquals(List.of(USER_ID), assignees);
        InOrder inOrder = inOrder(roleDao, userRoleDao);
        // lock first, or the list can go stale before the cascade runs
        inOrder.verify(roleDao).lockRole(TENANT_ID.getId(), ROLE_ID.getId());
        inOrder.verify(userRoleDao).findUserIdsByRoleId(ROLE_ID);
        // and the rows must still exist while they are being read
        inOrder.verify(roleDao).removeById(TENANT_ID, ROLE_ID.getId());
    }

    @Test
    public void testForeignOrMissingRoleIsNeitherReadNorDeleted() {
        // the tenant-scoped lock finds no row: another tenant's id, or one already deleted
        when(roleDao.lockRole(any(), any())).thenReturn(false);

        assertEquals(List.of(), service.deleteRoleAndCollectAssignees(TENANT_ID, ROLE_ID));
        verify(roleDao, never()).removeById(any(), any());
        verify(userRoleDao, never()).findUserIdsByRoleId(any());
    }

    /**
     * The two properties the mocks cannot see. Without either one the lock is taken and released
     * around a single statement, the assignee list goes stale again, and both tests above still
     * pass.
     */
    @Test
    public void testTheLockIsExclusiveAndOutlivesTheRead() throws NoSuchMethodException {
        assertNotNull(AnnotationUtils.findAnnotation(
                        BaseRoleService.class.getMethod("deleteRoleAndCollectAssignees", TenantId.class, RoleId.class),
                        Transactional.class),
                "the lock must be held until the delete commits");

        Query lockRole = AnnotationUtils.findAnnotation(
                RoleRepository.class.getMethod("lockRole", UUID.class, UUID.class), Query.class);
        assertNotNull(lockRole);
        assertTrue(lockRole.value().contains("FOR UPDATE"),
                "only FOR UPDATE conflicts with the FOR KEY SHARE an assignment takes on the role");
        assertTrue(lockRole.value().contains("tenant_id"), "a foreign role must not even be locked");
    }

    /**
     * A role delete takes the role row and then the assignment rows. The assignment path has to
     * take the same two in the same order, or the two transactions deadlock over each other.
     */
    @Test
    public void testTheAssignmentPathLocksRolesBeforeTouchingAssignments() throws NoSuchMethodException {
        Query lockRoles = AnnotationUtils.findAnnotation(
                UserRoleRepository.class.getMethod("lockRoles", List.class), Query.class);
        assertNotNull(lockRoles, "the assignment path must be able to lock the roles it assigns");
        assertTrue(lockRoles.value().contains("FOR UPDATE"));
        // fixed order, so two concurrent assignments cannot invert it between themselves
        assertTrue(lockRoles.value().contains("ORDER BY id"));
    }

}
