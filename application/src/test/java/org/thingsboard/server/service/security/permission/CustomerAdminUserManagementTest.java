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
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer-administrator capability (PE parity): a customer user holding an explicit USER grant
 * may manage peers inside their own customer. Everything here runs through the real
 * {@link CustomerUserPermissions} checker, because the guards that keep this from being a privilege
 * escalation live in that checker, not in the caller.
 */
public class CustomerAdminUserManagementTest {

    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());
    private static final CustomerId OTHER_CUSTOMER = new CustomerId(UUID.randomUUID());

    private static final String ALL = "{\"ALL\": [\"ALL\"]}";
    private static final String READ_ONLY =
            "{\"ALL\": [\"READ\", \"RPC_CALL\", \"READ_CREDENTIALS\", \"READ_ATTRIBUTES\", \"READ_TELEMETRY\"]}";

    private final PermissionChecker checker =
            new CustomerUserPermissions().getPermissionChecker(Resource.USER).orElseThrow();

    @SuppressWarnings("unchecked")
    private boolean can(SecurityUser actor, Operation operation, User target) {
        return checker.hasPermission(actor, operation, target.getId(), target);
    }

    @Test
    public void testCustomerAdminMayManagePeersInOwnCustomer() {
        SecurityUser admin = customerUser(CUSTOMER, ALL);
        User peer = customerUser(CUSTOMER);

        assertTrue(can(admin, Operation.READ, peer));
        assertTrue(can(admin, Operation.CREATE, peer));
        assertTrue(can(admin, Operation.WRITE, peer));
        assertTrue(can(admin, Operation.DELETE, peer));
    }

    @Test
    public void testRolelessCustomerUserIsUnchanged() {
        // the pre-existing behaviour: read peers, write only yourself
        SecurityUser plain = customerUser(CUSTOMER, null);
        User peer = customerUser(CUSTOMER);

        assertTrue(can(plain, Operation.READ, peer));
        assertFalse(can(plain, Operation.CREATE, peer));
        assertFalse(can(plain, Operation.WRITE, peer));
        assertFalse(can(plain, Operation.DELETE, peer));

        User self = new User(plain.getId());
        self.setAuthority(Authority.CUSTOMER_USER);
        self.setCustomerId(CUSTOMER);
        assertTrue(can(plain, Operation.WRITE, self));
    }

    @Test
    public void testSeededCustomerUserRoleDoesNotGrantIt() {
        SecurityUser plain = customerUser(CUSTOMER, READ_ONLY);
        User peer = customerUser(CUSTOMER);

        assertTrue(can(plain, Operation.READ, peer));
        assertFalse(can(plain, Operation.CREATE, peer));
        assertFalse(can(plain, Operation.WRITE, peer));
        assertFalse(can(plain, Operation.DELETE, peer));
    }

    @Test
    public void testCustomerAdminCannotReachAnotherCustomer() {
        SecurityUser admin = customerUser(CUSTOMER, ALL);
        User foreign = customerUser(OTHER_CUSTOMER);

        assertFalse(can(admin, Operation.READ, foreign));
        assertFalse(can(admin, Operation.CREATE, foreign));
        assertFalse(can(admin, Operation.WRITE, foreign));
        assertFalse(can(admin, Operation.DELETE, foreign));
    }

    @Test
    public void testCustomerAdminCannotCreateOrTouchATenantAdmin() {
        SecurityUser admin = customerUser(CUSTOMER, ALL);
        User tenantAdmin = new User(new UserId(UUID.randomUUID()));
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setCustomerId(CUSTOMER);

        assertFalse(can(admin, Operation.CREATE, tenantAdmin));
        assertFalse(can(admin, Operation.WRITE, tenantAdmin));
        assertFalse(can(admin, Operation.DELETE, tenantAdmin));
        assertFalse(can(admin, Operation.READ, tenantAdmin));
    }

    private static User customerUser(CustomerId customerId) {
        User user = new User(new UserId(UUID.randomUUID()));
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setCustomerId(customerId);
        return user;
    }

    private static SecurityUser customerUser(CustomerId customerId, String permissionsJson) {
        SecurityUser user = new SecurityUser();
        user.setId(new UserId(UUID.randomUUID()));
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setCustomerId(customerId);
        if (permissionsJson != null) {
            Role role = new Role();
            role.setType(RoleType.GENERIC);
            role.setPermissions(JacksonUtil.toJsonNode(permissionsJson));
            user.setUserPermissions(DefaultUserPermissionsService.mergeRolePermissions(List.of(role)));
        }
        return user;
    }

}
