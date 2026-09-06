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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.dao.entity.EntityCountService;
import org.thingsboard.server.dao.service.DataValidator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two roles PE autogenerates. Their exact permission maps are the contract — "Customer
 * Administrator" is what turns on customer-user management, and "Customer User" must NOT.
 */
public class BaseRoleServiceDefaultsTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());

    private final RoleDao roleDao = mock(RoleDao.class);
    private final BaseRoleService service = new BaseRoleService();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(service, "roleDao", roleDao);
        ReflectionTestUtils.setField(service, "entityCountService", mock(EntityCountService.class));
        ReflectionTestUtils.setField(service, "eventPublisher", mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "roleDataValidator", mock(DataValidator.class));
        when(roleDao.save(any(), any())).thenAnswer(i -> i.getArgument(1));
        when(roleDao.findByTenantIdAndName(any(), any())).thenReturn(Optional.empty());
    }

    private Map<String, Role> created() {
        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(roleDao, org.mockito.Mockito.atLeastOnce()).save(eq(TENANT_ID), saved.capture());
        return saved.getAllValues().stream().collect(Collectors.toMap(Role::getName, r -> r));
    }

    @Test
    public void testBothRolesAreCreatedWithPePermissionMaps() {
        service.createDefaultRoles(TENANT_ID);
        Map<String, Role> roles = created();
        assertEquals(2, roles.size());

        Role admin = roles.get(BaseRoleService.CUSTOMER_ADMIN_ROLE);
        assertEquals(RoleType.GENERIC, admin.getType());
        assertEquals(List.of("ALL"), operations(admin, "ALL"));

        Role user = roles.get(BaseRoleService.CUSTOMER_USER_ROLE);
        assertEquals(RoleType.GENERIC, user.getType());
        assertEquals(List.of("READ", "RPC_CALL", "READ_CREDENTIALS", "READ_ATTRIBUTES", "READ_TELEMETRY"),
                operations(user, "ALL"));
    }

    @Test
    public void testExistingRolesAreLeftAlone() {
        when(roleDao.findByTenantIdAndName(TENANT_ID.getId(), BaseRoleService.CUSTOMER_ADMIN_ROLE))
                .thenReturn(Optional.of(new Role()));

        service.createDefaultRoles(TENANT_ID);

        // only the missing one is created - a tenant that edited or renamed the admin role keeps it
        assertEquals(List.of(BaseRoleService.CUSTOMER_USER_ROLE), List.copyOf(created().keySet()));
    }

    @Test
    public void testNothingIsCreatedWhenBothExist() {
        when(roleDao.findByTenantIdAndName(any(), any())).thenReturn(Optional.of(new Role()));
        service.createDefaultRoles(TENANT_ID);
        verify(roleDao, never()).save(any(), any());
    }

    @Test
    public void testALostRaceIsSwallowed() {
        // a concurrent listing wins and trips role_name_unq_key: the role exists, which is the point
        when(roleDao.save(any(), any())).thenThrow(new RuntimeException("role_name_unq_key"));
        assertDoesNotThrow(() -> service.createDefaultRoles(TENANT_ID));
    }

    private static List<String> operations(Role role, String resource) {
        return role.getPermissions().get(resource).valueStream()
                .map(n -> n.asText()).collect(Collectors.toList());
    }

}
