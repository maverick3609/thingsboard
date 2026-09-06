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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.ShortCustomerInfo;
import org.thingsboard.server.dao.service.DaoSqlTest;

import static org.junit.Assert.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an anonymous public-dashboard viewer may do (PE parity, see
 * {@code DefaultUserPermissionsService.PUBLIC_USER_PERMISSIONS}).
 *
 * A public user is synthetic - {@code AbstractAuthenticationProvider} builds it as a CUSTOMER_USER
 * of the public customer with {@code UserId(NULL_UUID)} and no database row - so unlike every other
 * role holder there is nothing to assign a role TO. The permission set is therefore fixed in code.
 *
 * Stock CE gives this viewer everything {@code CustomerUserPermissions.customerEntityPermissionChecker}
 * allows on the public customer's entities, which measurably included reading device CREDENTIALS and
 * writing attributes, timeseries and the device itself. Anyone holding the public link had those.
 */
@DaoSqlTest
public class PublicUserPermissionsControllerTest extends AbstractControllerTest {

    private Dashboard dashboard;
    private Device device;

    @Before
    public void publishADashboardAndLoginAnonymously() throws Exception {
        loginTenantAdmin();

        Dashboard toPublish = new Dashboard();
        toPublish.setTitle("Public dashboard");
        dashboard = doPost("/api/dashboard", toPublish, Dashboard.class);
        Dashboard published = doPost("/api/customer/public/dashboard/" + dashboard.getId().getId(), Dashboard.class);

        Device toPublish2 = new Device();
        toPublish2.setName("Public device");
        toPublish2.setType("default");
        device = doPost("/api/device", toPublish2, Device.class);
        doPost("/api/customer/public/device/" + device.getId().getId(), Device.class);

        resetTokens();
        JsonNode tokens = doPost("/api/auth/login/public",
                JacksonUtil.toJsonNode("{\"publicId\": \"" + publicCustomerId(published) + "\"}"), JsonNode.class);
        this.token = tokens.get("token").asText();
    }

    /** The feature must keep working: a public dashboard and the data its widgets render. */
    @Test
    public void publicViewerCanStillRenderTheDashboard() throws Exception {
        String d = device.getId().getId().toString();
        doGet("/api/dashboard/" + dashboard.getId().getId()).andExpect(status().isOk());
        doGet("/api/device/" + d).andExpect(status().isOk());
        doGet("/api/plugins/telemetry/DEVICE/" + d + "/values/attributes/SHARED_SCOPE").andExpect(status().isOk());
        doGet("/api/plugins/telemetry/DEVICE/" + d + "/keys/timeseries").andExpect(status().isOk());
        doGet("/api/alarm/DEVICE/" + d + "?pageSize=10&page=0").andExpect(status().isOk());
    }

    /**
     * The device access token. Stock CE answered 200 here - anyone with the public dashboard link
     * could lift the credentials and then publish as that device indefinitely, from anywhere.
     */
    @Test
    public void publicViewerCannotReadDeviceCredentials() throws Exception {
        doGet("/api/device/" + device.getId().getId() + "/credentials").andExpect(status().isForbidden());
    }

    @Test
    public void publicViewerCannotWriteAttributesOrTelemetry() throws Exception {
        String d = device.getId().getId().toString();
        doPostAsync("/api/plugins/telemetry/DEVICE/" + d + "/SHARED_SCOPE",
                JacksonUtil.toJsonNode("{\"injected\":1}"), 5000L).andExpect(status().isForbidden());
        doPostAsync("/api/plugins/telemetry/DEVICE/" + d + "/timeseries/ANY",
                JacksonUtil.toJsonNode("{\"injected\":2}"), 5000L).andExpect(status().isForbidden());
    }

    @Test
    public void publicViewerCannotModifyOrDeleteTheDevice() throws Exception {
        Device renamed = JacksonUtil.clone(device);
        renamed.setName("Renamed by an anonymous viewer");
        renamed.setVersion(null);
        doPost("/api/device", renamed).andExpect(status().isForbidden());
        doDelete("/api/device/" + device.getId().getId()).andExpect(status().isForbidden());
    }

    /**
     * RPC survives the clamp, deliberately: PE's public entity-group set carries {@code RPC_CALL},
     * ours mirrors it, and stock CE allowed it too - measured identically with the clamp disabled.
     * So this is pre-existing behaviour, not something the clamp introduced.
     *
     * It is nonetheless the one write-shaped capability an anonymous link holder keeps: whoever has
     * the public dashboard URL can actuate the devices on it. Delete {@code RPC_CALL} from
     * {@code DefaultUserPermissionsService.PUBLIC_USER_PERMISSIONS} to close it.
     *
     * Authorized + device offline means the async result never arrives; a refusal would have
     * completed as 403 straight away. Asserting the timeout is therefore asserting "not refused".
     */
    @Test
    public void publicViewerCanStillSendRpcAsInPe() {
        assertThrows(IllegalStateException.class, () ->
                doPostAsync("/api/rpc/oneway/" + device.getId().getId(),
                        JacksonUtil.toJsonNode("{\"method\":\"probe\",\"params\":{}}"), 5000L));
    }

    private static String publicCustomerId(Dashboard dashboard) {
        for (ShortCustomerInfo assigned : dashboard.getAssignedCustomers()) {
            if (assigned.isPublic()) {
                return assigned.getCustomerId().getId().toString();
            }
        }
        throw new IllegalStateException("dashboard was not assigned to a public customer");
    }

}
