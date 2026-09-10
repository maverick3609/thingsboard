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
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CREATE and DELETE on assets and devices are off for customer users until a role names them.
 *
 * <p>The dangerous direction is the permissive one: the baseline these checkers extend uses
 * {@code granted}, which reads "no roles" as legacy full access, and the whole point of wiring
 * CREATE through {@code explicitlyGranted} instead is that a role-less customer user - the common
 * case on every existing install - stays exactly as locked out as before.
 */
class CustomerCreatePermissionsTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final CustomerId OTHER_CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    private static final PermissionChecker ASSET_CHECKER =
            new CustomerUserPermissions().getPermissionChecker(Resource.ASSET).orElseThrow();
    private static final PermissionChecker DEVICE_CHECKER =
            new CustomerUserPermissions().getPermissionChecker(Resource.DEVICE).orElseThrow();

    @Test
    void roleLessCustomerUserCannotCreate() {
        SecurityUser user = customerUser(null);
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, asset(CUSTOMER_ID))).isFalse();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE)).isFalse();
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.CREATE, null, device(CUSTOMER_ID))).isFalse();
    }

    @Test
    void roleWithoutCreateCannotCreate() {
        SecurityUser user = customerUser("{\"ASSET\": [\"READ\", \"WRITE\"]}");
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, asset(CUSTOMER_ID))).isFalse();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE)).isFalse();
    }

    @Test
    void roleNamingCreateCanCreateIntoOwnCustomer() {
        SecurityUser user = customerUser("{\"ASSET\": [\"CREATE\"]}");
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, asset(CUSTOMER_ID))).isTrue();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE)).isTrue();
    }

    @Test
    void createStaysInsideTheUsersOwnCustomerAndTenant() {
        SecurityUser user = customerUser("{\"ASSET\": [\"CREATE\"]}");

        Asset siblingCustomer = asset(OTHER_CUSTOMER_ID);
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, siblingCustomer)).isFalse();

        Asset otherTenant = asset(CUSTOMER_ID);
        otherTenant.setTenantId(TenantId.fromUUID(UUID.randomUUID()));
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, otherTenant)).isFalse();

        Asset unowned = asset(null);
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.CREATE, null, unowned)).isFalse();
    }

    /** Each resource carries its own grant: ASSET:CREATE must not spill into devices. */
    @Test
    void createIsPerResource() {
        SecurityUser user = customerUser("{\"ASSET\": [\"CREATE\"]}");
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.CREATE, null, device(CUSTOMER_ID))).isFalse();

        SecurityUser deviceUser = customerUser("{\"DEVICE\": [\"CREATE\"]}");
        assertThat(DEVICE_CHECKER.hasPermission(deviceUser, Operation.CREATE, null, device(CUSTOMER_ID))).isTrue();
        assertThat(ASSET_CHECKER.hasPermission(deviceUser, Operation.CREATE, null, asset(CUSTOMER_ID))).isFalse();
    }

    @Test
    void roleNamingDeleteCanDeleteOwnAssets() {
        SecurityUser user = customerUser("{\"ASSET\": [\"DELETE\"]}");
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.DELETE, null, asset(CUSTOMER_ID))).isTrue();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.DELETE, null, asset(OTHER_CUSTOMER_ID))).isFalse();
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.DELETE, null, device(CUSTOMER_ID))).isFalse();
    }

    @Test
    void roleLessCustomerUserCannotDeleteAssets() {
        SecurityUser user = customerUser(null);
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.DELETE, null, asset(CUSTOMER_ID))).isFalse();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.DELETE)).isFalse();
    }

    @Test
    void roleNamingDeleteCanDeleteOwnDevices() {
        SecurityUser user = customerUser("{\"DEVICE\": [\"DELETE\"]}");
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.DELETE, null, device(CUSTOMER_ID))).isTrue();
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.DELETE, null, device(OTHER_CUSTOMER_ID))).isFalse();
        // the grant is per-resource: naming DEVICE:DELETE must not reach assets
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.DELETE, null, asset(CUSTOMER_ID))).isFalse();
    }

    @Test
    void roleLessCustomerUserCannotDeleteDevices() {
        SecurityUser user = customerUser(null);
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.DELETE, null, device(CUSTOMER_ID))).isFalse();
        assertThat(DEVICE_CHECKER.hasPermission(user, Operation.DELETE)).isFalse();
    }

    /** The rest of the customer baseline is untouched - only CREATE and DELETE go through the gate. */
    @Test
    void baselineOperationsAreUnchanged() {
        SecurityUser user = customerUser(null);
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.READ, null, asset(CUSTOMER_ID))).isTrue();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.WRITE, null, asset(CUSTOMER_ID))).isTrue();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.READ, null, asset(OTHER_CUSTOMER_ID))).isFalse();
        assertThat(ASSET_CHECKER.hasPermission(user, Operation.READ_TELEMETRY, null, asset(CUSTOMER_ID))).isTrue();
    }

    /**
     * The anonymous holder of a public dashboard link arrives as a CUSTOMER_USER carrying the
     * clamped permission set from {@code DefaultUserPermissionsService}. Nothing in it names
     * CREATE, so opening CREATE must not have opened it to the whole internet.
     */
    @Test
    void publicDashboardViewerCannotCreate() {
        SecurityUser publicViewer = new SecurityUser();
        publicViewer.setAuthority(Authority.CUSTOMER_USER);
        publicViewer.setTenantId(TENANT_ID);
        publicViewer.setCustomerId(CUSTOMER_ID);
        publicViewer.setUserPermissions(DefaultUserPermissionsService.mergeRolePermissions(List.of(
                role("{\"ALL\": [\"READ\", \"READ_ATTRIBUTES\", \"READ_TELEMETRY\"]}"))));

        assertThat(ASSET_CHECKER.hasPermission(publicViewer, Operation.CREATE, null, asset(CUSTOMER_ID))).isFalse();
        assertThat(DEVICE_CHECKER.hasPermission(publicViewer, Operation.CREATE, null, device(CUSTOMER_ID))).isFalse();
        assertThat(ASSET_CHECKER.hasPermission(publicViewer, Operation.DELETE, null, asset(CUSTOMER_ID))).isFalse();
        assertThat(DEVICE_CHECKER.hasPermission(publicViewer, Operation.DELETE, null, device(CUSTOMER_ID))).isFalse();
        // ... while the clamp's own operations still work, so the assertion above is not vacuous
        assertThat(ASSET_CHECKER.hasPermission(publicViewer, Operation.READ, null, asset(CUSTOMER_ID))).isTrue();
    }

    private static Role role(String permissionsJson) {
        Role role = new Role();
        role.setType(RoleType.GENERIC);
        role.setPermissions(JacksonUtil.toJsonNode(permissionsJson));
        return role;
    }

    private static SecurityUser customerUser(String permissionsJson) {
        SecurityUser user = new SecurityUser();
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setTenantId(TENANT_ID);
        user.setCustomerId(CUSTOMER_ID);
        if (permissionsJson != null) {
            user.setUserPermissions(DefaultUserPermissionsService.mergeRolePermissions(List.of(role(permissionsJson))));
        }
        return user;
    }

    private static Asset asset(CustomerId customerId) {
        Asset asset = new Asset();
        asset.setTenantId(TENANT_ID);
        asset.setCustomerId(customerId);
        return asset;
    }

    private static Device device(CustomerId customerId) {
        Device device = new Device();
        device.setTenantId(TENANT_ID);
        device.setCustomerId(customerId);
        return device;
    }

}
