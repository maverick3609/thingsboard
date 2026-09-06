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
package org.thingsboard.server.controller;

import org.junit.Assert;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a customer user may do to their OWN user record.
 *
 * The customer-administrator change (ledger RB35) added CUSTOMER_USER to the authority list of
 * DELETE /api/user/{userId}. CustomerUserPermissions waves through every operation on yourself, so
 * that widening also handed customer users the ability to delete their own account - something the
 * old SYS_ADMIN/TENANT_ADMIN list refused outright. RB41 put an explicit guard back in the
 * controller; this pins both halves of the resulting contract.
 */
@DaoSqlTest
public class CustomerUserSelfServiceControllerTest extends AbstractControllerTest {

    /**
     * The PE-parity read-only role grants no USER:WRITE, and the entity-scoped role gate used to
     * exempt only a self READ - so holding it made a customer user unable to edit their own name,
     * phone or language. PE grants PROFILE:[ALL] alongside its read-only set for the same reason.
     */
    @Test
    public void testReadOnlyRoleStillLetsAUserEditTheirOwnProfile() throws Exception {
        loginTenantAdmin();
        Role readOnly = new Role();
        readOnly.setName("Read only for self-service test");
        readOnly.setType(RoleType.GENERIC);
        readOnly.setPermissions(JacksonUtil.toJsonNode(
                "{\"ALL\": [\"READ\", \"RPC_CALL\", \"READ_CREDENTIALS\", \"READ_ATTRIBUTES\", \"READ_TELEMETRY\"]}"));
        Role savedRole = doPost("/api/role", readOnly, Role.class);

        loginCustomerUser();
        User self = doGet("/api/auth/user", User.class);

        loginTenantAdmin();
        doPost("/api/user/" + self.getId().getId() + "/roles",
                List.of(savedRole.getId().getId().toString())).andExpect(status().isOk());

        loginCustomerUser();
        self = doGet("/api/auth/user", User.class);
        self.setFirstName("Renamed");
        User saved = doPost("/api/user", self, User.class);
        Assert.assertEquals("Renamed", saved.getFirstName());

        // the role is still doing its job on everything that is not the user themselves
        doGet("/api/tenant/devices?pageSize=10&page=0").andExpect(status().isForbidden());
    }

    @Test
    public void testCustomerUserMayNotDeleteTheirOwnAccount() throws Exception {
        loginCustomerUser();
        User self = doGet("/api/auth/user", User.class);

        doDelete("/api/user/" + self.getId().getId()).andExpect(status().isForbidden());

        // still there, and still able to log in
        loginCustomerUser();
        Assert.assertEquals(self.getId(), doGet("/api/auth/user", User.class).getId());
    }

}
