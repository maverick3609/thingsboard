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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.service.DaoSqlTest;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class LicenseControllerTest extends AbstractControllerTest {

    private User customerUser;

    @Before
    public void beforeTest() throws Exception {
        loginTenantAdmin();
        Customer customer = new Customer();
        customer.setTitle("Licence test customer");
        Customer saved = doPost("/api/customer", customer, Customer.class);

        User user = new User();
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setTenantId(tenantId);
        user.setCustomerId(saved.getId());
        user.setEmail("licence.customer@thingsboard.org");
        customerUser = createUserAndLogin(user, "customer");
    }

    @After
    public void afterTest() throws Exception {
        loginSysAdmin();
    }

    @Test
    public void tenantAdminCanReadLicenseInfo() throws Exception {
        loginTenantAdmin();
        doGet("/api/license/info").andExpect(status().isOk());
    }

    @Test
    public void sysAdminCanReadLicenseInfo() throws Exception {
        loginSysAdmin();
        doGet("/api/license/info").andExpect(status().isOk());
    }

    @Test
    public void customerUserIsForbidden() throws Exception {
        login(customerUser.getEmail(), "customer");
        doGet("/api/license/info").andExpect(status().isForbidden());
    }

    @Test
    public void anonymousIsUnauthorized() throws Exception {
        resetTokens();
        doGet("/api/license/info").andExpect(status().isUnauthorized());
    }
}
