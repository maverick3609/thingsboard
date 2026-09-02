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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.license.LicenseInfo;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.license.LicenseService;
import org.thingsboard.server.dao.service.DaoSqlTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code license.enforcement.enabled=false} in the test properties activates {@link
 * org.thingsboard.server.dao.license.NoopLicenseService}, whose {@code getInfo()} always returns
 * {@code null} -- fine for the status-only tests below, but the field-level assertions need a real
 * payload, so {@link LicenseService} is {@code @MockitoBean}-replaced (same pattern as {@code
 * DashboardReportControllerTest}) and stubbed only where a test reads the body.
 */
@DaoSqlTest
public class LicenseControllerTest extends AbstractControllerTest {

    @MockitoBean
    private LicenseService licenseService;

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

    @Test
    public void tenantAdminDoesNotSeeTheCustomerNameOrInstanceId() throws Exception {
        when(licenseService.getInfo()).thenReturn(fullLicenseInfo());
        loginTenantAdmin();

        LicenseInfo info = doGet("/api/license/info", LicenseInfo.class);

        assertThat(info.getCustomer()).isNull();
        assertThat(info.getInstanceId()).isNull();
        assertThat(info.getMaxDevices()).isNotNull();     // the useful part survives
    }

    @Test
    public void sysAdminStillSeesTheCustomerNameAndInstanceId() throws Exception {
        when(licenseService.getInfo()).thenReturn(fullLicenseInfo());
        loginSysAdmin();

        LicenseInfo info = doGet("/api/license/info", LicenseInfo.class);

        assertThat(info.getCustomer()).isNotNull();
        assertThat(info.getInstanceId()).isNotNull();
    }

    /** A fully populated licence, standing in for what {@code DefaultLicenseService} would return. */
    private static LicenseInfo fullLicenseInfo() {
        LicenseInfo info = new LicenseInfo();
        info.setCustomer("Licence test customer");
        info.setInstanceId("9f3a1c02-4b6d-4c3e-9a10-2f7c5d3e8b71");
        info.setExpiresAt(1795033600L);
        info.setDaysRemaining(211L);
        info.setDevices(3L);
        info.setMaxDevices(5000L);
        info.setAssets(1L);
        info.setMaxAssets(2000L);
        return info;
    }
}
