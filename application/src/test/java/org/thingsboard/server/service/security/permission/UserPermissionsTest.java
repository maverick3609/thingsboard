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
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.query.RelationsQueryFilter;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.data.relation.RelationEntityTypeFilter;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Collections;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UserPermissionsTest {

    private static Role role(String permissionsJson) {
        Role role = new Role();
        role.setType(RoleType.GENERIC);
        role.setPermissions(JacksonUtil.toJsonNode(permissionsJson));
        return role;
    }

    private static SecurityUser user(Authority authority, MergedUserPermissions permissions) {
        SecurityUser user = new SecurityUser();
        user.setAuthority(authority);
        user.setUserPermissions(permissions);
        return user;
    }

    @Test
    public void testMergeUnionsRoles() {
        MergedUserPermissions merged = DefaultUserPermissionsService.mergeRolePermissions(List.of(
                role("{\"DEVICE\": [\"READ\"]}"),
                role("{\"DEVICE\": [\"WRITE\"], \"ASSET\": [\"READ\"]}")));
        assertTrue(merged.hasGenericPermission(Resource.DEVICE, Operation.READ));
        assertTrue(merged.hasGenericPermission(Resource.DEVICE, Operation.WRITE));
        assertTrue(merged.hasGenericPermission(Resource.ASSET, Operation.READ));
        assertFalse(merged.hasGenericPermission(Resource.ASSET, Operation.WRITE));
        assertFalse(merged.hasGenericPermission(Resource.DASHBOARD, Operation.READ));
    }

    @Test
    public void testMergeWildcards() {
        MergedUserPermissions allResources = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"ALL\": [\"READ\"]}")));
        assertTrue(allResources.hasGenericPermission(Resource.DEVICE, Operation.READ));
        assertFalse(allResources.hasGenericPermission(Resource.DEVICE, Operation.WRITE));

        MergedUserPermissions allOps = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"ALL\"]}")));
        assertTrue(allOps.hasGenericPermission(Resource.DEVICE, Operation.DELETE));
        assertFalse(allOps.hasGenericPermission(Resource.ASSET, Operation.READ));
    }

    @Test
    public void testMergeSkipsUnknownNamesAndEmptyRoles() {
        MergedUserPermissions merged = DefaultUserPermissionsService.mergeRolePermissions(List.of(
                role("{\"BOGUS\": [\"READ\"], \"DEVICE\": [\"READ\", \"FLY\"]}")));
        assertTrue(merged.hasGenericPermission(Resource.DEVICE, Operation.READ));
        assertFalse(merged.hasGenericPermission(Resource.DEVICE, Operation.WRITE));

        assertNull(DefaultUserPermissionsService.mergeRolePermissions(Collections.emptyList()));
        assertNull(DefaultUserPermissionsService.mergeRolePermissions(null));
    }

    @Test
    public void testGrantedFallbacks() {
        // role-less user keeps legacy access
        assertTrue(UserPermissionsUtil.granted(user(Authority.CUSTOMER_USER, null), Resource.DEVICE, Operation.DELETE));
        // non-restrictable authorities are never gated
        assertTrue(UserPermissionsUtil.granted(user(Authority.SYS_ADMIN, null), Resource.DEVICE, Operation.DELETE));
        assertTrue(UserPermissionsUtil.granted(user(Authority.MFA_CONFIGURATION_TOKEN, null), Resource.DEVICE, Operation.DELETE));
    }

    /**
     * Roles restrict customer users only - a tenant admin owns their domain outright. Enforced
     * twice: DefaultUserPermissionsService never loads permissions for them, and this gate ignores
     * any that some other call site attaches anyway.
     */
    @Test
    public void testTenantAdminIsNeverRoleRestricted() {
        MergedUserPermissions readOnlyDevices = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"READ\"]}")));
        SecurityUser tenantAdmin = user(Authority.TENANT_ADMIN, readOnlyDevices);

        assertTrue(UserPermissionsUtil.granted(tenantAdmin, Resource.DASHBOARD, Operation.READ));
        assertTrue(UserPermissionsUtil.granted(tenantAdmin, Resource.DEVICE, Operation.DELETE));
        assertTrue(UserPermissionsUtil.granted(tenantAdmin, Resource.ROLE, Operation.WRITE));
        assertTrue(UserPermissionsUtil.grantedEntityQuery(tenantAdmin, relationsQuery(EntityType.ASSET)));

        // ... while the very same role still restricts a customer user
        SecurityUser customerUser = user(Authority.CUSTOMER_USER, readOnlyDevices);
        assertFalse(UserPermissionsUtil.granted(customerUser, Resource.DASHBOARD, Operation.READ));
    }

    @Test
    public void testGrantedEntityQueryGatesRelationsQueryOnReturnedTypes() {
        MergedUserPermissions readOnlyDevices = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"READ\"]}")));
        SecurityUser user = user(Authority.CUSTOMER_USER, readOnlyDevices);

        // the root entity type is irrelevant: what leaves the system is the RELATED entities
        assertTrue(UserPermissionsUtil.grantedEntityQuery(user, relationsQuery(EntityType.DEVICE)));
        assertFalse(UserPermissionsUtil.grantedEntityQuery(user, relationsQuery(EntityType.ASSET)));
        assertFalse(UserPermissionsUtil.grantedEntityQuery(user, relationsQuery(EntityType.DEVICE, EntityType.ASSET)));
        // no entity types named => every relation-queryable type can come back
        assertFalse(UserPermissionsUtil.grantedEntityQuery(user, relationsQuery()));

        MergedUserPermissions readAll = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"ALL\": [\"READ\"]}")));
        assertTrue(UserPermissionsUtil.grantedEntityQuery(user(Authority.CUSTOMER_USER, readAll), relationsQuery()));
    }

    private static RelationsQueryFilter relationsQuery(EntityType... relatedTypes) {
        RelationsQueryFilter filter = new RelationsQueryFilter();
        filter.setDirection(EntitySearchDirection.FROM);
        filter.setFilters(relatedTypes.length == 0 ? List.of()
                : List.of(new RelationEntityTypeFilter("Contains", List.of(relatedTypes))));
        return filter;
    }

    @Test
    public void testGrantedRestrictsByRole() {
        MergedUserPermissions readOnlyDevices = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"READ\"]}")));
        SecurityUser user = user(Authority.CUSTOMER_USER, readOnlyDevices);
        assertTrue(UserPermissionsUtil.granted(user, Resource.DEVICE, Operation.READ));
        assertFalse(UserPermissionsUtil.granted(user, Resource.DEVICE, Operation.WRITE));
        assertFalse(UserPermissionsUtil.granted(user, Resource.DASHBOARD, Operation.READ));
    }

    /**
     * Every authenticated session bootstraps with GET /api/user/{self}, and editing your own profile
     * is something stock CE gives every customer user, so the entity-scoped gate exempts a self READ
     * and a self WRITE. DELETE is never exempt.
     */
    @Test
    public void testSelfUserReadAndWriteAreNeverRoleGated() {
        MergedUserPermissions readOnlyDevices = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"READ\"]}")));
        SecurityUser user = user(Authority.CUSTOMER_USER, readOnlyDevices);
        UserId self = new UserId(UUID.randomUUID());
        user.setId(self);

        assertTrue(UserPermissionsUtil.granted(user, Resource.USER, Operation.READ, self));
        // your own profile: a role that omits USER:WRITE must not stop you editing yourself
        assertTrue(UserPermissionsUtil.granted(user, Resource.USER, Operation.WRITE, self));
        // ... but only yourself, and only up to WRITE
        assertFalse(UserPermissionsUtil.granted(user, Resource.USER, Operation.READ, new UserId(UUID.randomUUID())));
        assertFalse(UserPermissionsUtil.granted(user, Resource.USER, Operation.WRITE, new UserId(UUID.randomUUID())));
        assertFalse(UserPermissionsUtil.granted(user, Resource.USER, Operation.DELETE, self));
        assertFalse(UserPermissionsUtil.granted(user, Resource.USER, Operation.READ));
        // the exemption is scoped to USER: a self id must not unlock another resource
        assertFalse(UserPermissionsUtil.granted(user, Resource.DASHBOARD, Operation.READ, self));
    }

    /**
     * The customer-administrator capability is opt-in: it must stay OFF for a role-less customer
     * user, who would otherwise inherit peer-user management the day the feature shipped.
     */
    @Test
    public void testExplicitlyGrantedHasNoLegacyFallback() {
        MergedUserPermissions all = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"ALL\": [\"ALL\"]}")));
        MergedUserPermissions readOnly = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"ALL\": [\"READ\", \"RPC_CALL\", \"READ_CREDENTIALS\", \"READ_ATTRIBUTES\", \"READ_TELEMETRY\"]}")));

        // role-less: granted() says yes (legacy access), explicitlyGranted() says no
        SecurityUser roleless = user(Authority.CUSTOMER_USER, null);
        assertTrue(UserPermissionsUtil.granted(roleless, Resource.USER, Operation.WRITE));
        assertFalse(UserPermissionsUtil.explicitlyGranted(roleless, Resource.USER, Operation.WRITE));

        // Customer Administrator (PE's ALL:ALL) turns it on
        SecurityUser admin = user(Authority.CUSTOMER_USER, all);
        assertTrue(UserPermissionsUtil.explicitlyGranted(admin, Resource.USER, Operation.CREATE));
        assertTrue(UserPermissionsUtil.explicitlyGranted(admin, Resource.USER, Operation.WRITE));
        assertTrue(UserPermissionsUtil.explicitlyGranted(admin, Resource.USER, Operation.DELETE));

        // Customer User (PE's read-only map) does not
        SecurityUser plain = user(Authority.CUSTOMER_USER, readOnly);
        assertTrue(UserPermissionsUtil.explicitlyGranted(plain, Resource.USER, Operation.READ));
        assertFalse(UserPermissionsUtil.explicitlyGranted(plain, Resource.USER, Operation.CREATE));
        assertFalse(UserPermissionsUtil.explicitlyGranted(plain, Resource.USER, Operation.WRITE));
        assertFalse(UserPermissionsUtil.explicitlyGranted(plain, Resource.USER, Operation.DELETE));
    }

    /**
     * checkCustomerId guards every customer-scoped list, so a customer user must be able to read
     * the customer they belong to whatever else the role denies. Tenant admins are not exempt.
     */
    @Test
    public void testOwnCustomerReadIsExemptForCustomerUsersOnly() {
        MergedUserPermissions readOnlyDevices = DefaultUserPermissionsService.mergeRolePermissions(
                List.of(role("{\"DEVICE\": [\"READ\"]}")));
        CustomerId own = new CustomerId(UUID.randomUUID());
        CustomerId other = new CustomerId(UUID.randomUUID());

        SecurityUser customerUser = user(Authority.CUSTOMER_USER, readOnlyDevices);
        customerUser.setCustomerId(own);
        assertTrue(UserPermissionsUtil.granted(customerUser, Resource.CUSTOMER, Operation.READ, own));
        assertFalse(UserPermissionsUtil.granted(customerUser, Resource.CUSTOMER, Operation.READ, other));
        // WRITE is exempt for USER only - stock CE gives a customer user no write on their own Customer
        assertFalse(UserPermissionsUtil.granted(customerUser, Resource.CUSTOMER, Operation.WRITE, own));

        // a tenant admin is not gated at all now, own customer or not
        SecurityUser tenantAdmin = user(Authority.TENANT_ADMIN, readOnlyDevices);
        tenantAdmin.setCustomerId(own);
        assertTrue(UserPermissionsUtil.granted(tenantAdmin, Resource.CUSTOMER, Operation.READ, other));
    }

}
