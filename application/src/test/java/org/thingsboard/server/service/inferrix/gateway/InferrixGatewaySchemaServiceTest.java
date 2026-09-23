// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The schema document is fetched once per gateway and reused.
 *
 * <p>It changes only when the gateway's build changes, and it is not small — the real document is
 * roughly 177 schemas — so fetching it per form would put a LAN round trip in front of every field
 * an operator opens.
 */
class InferrixGatewaySchemaServiceTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE_A = new DeviceId(UUID.randomUUID());
    private static final DeviceId DEVICE_B = new DeviceId(UUID.randomUUID());

    private static final String DOCUMENT =
            "{\"families\":{\"dataSource\":{\"MODBUS_IP.DS\":{\"type\":\"object\"}}},"
                    + "\"components\":{\"schemas\":{}}}";

    private InferrixGatewayAccess access;
    private InferrixGatewaySchemaService service;

    @BeforeEach
    void setUp() throws Exception {
        access = mock(InferrixGatewayAccess.class);
        when(access.call(any(), any(), eq("GET"), eq(InferrixGatewaySchemaService.SCHEMAS_PATH), any(), any()))
                .thenReturn(new GatewayResponse(200, DOCUMENT));
        service = new InferrixGatewaySchemaService(access);
    }

    @Test
    void theWholeDocumentIsCachedPerDevice() throws Exception {
        JsonNode first = service.schemas(TENANT_ID, DEVICE_A);
        JsonNode second = service.schemas(TENANT_ID, DEVICE_A);

        assertThat(first.at("/families/dataSource/MODBUS_IP.DS").isObject()).isTrue();
        assertThat(second).isSameAs(first);
        verify(access, times(1)).call(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void theWholeDocumentIsCachedRatherThanOneEntryPerType() throws Exception {
        // Not a taste choice. Nested types are $refs into the document's own components.schemas, so
        // a per-(family, type) cache would either duplicate that block in every entry or hold
        // entries whose refs point at something the cache no longer has.
        JsonNode document = service.schemas(TENANT_ID, DEVICE_A);

        assertThat(document.has("families")).isTrue();
        assertThat(document.at("/components/schemas").isObject()).isTrue();
    }

    @Test
    void twoGatewaysDoNotShareAnEntry() throws Exception {
        when(access.call(any(), eq(DEVICE_B), anyString(), anyString(), any(), any()))
                .thenReturn(new GatewayResponse(200,
                        "{\"families\":{\"dataSource\":{\"BACNET_IP.DS\":{}}},"
                                + "\"components\":{\"schemas\":{}}}"));

        // Two gateways can legitimately run different stack versions with different fields. Sharing
        // one entry would render a form for hardware that does not have those inputs.
        assertThat(service.schemas(TENANT_ID, DEVICE_A).has("families")).isTrue();
        assertThat(service.schemas(TENANT_ID, DEVICE_B).at("/families/dataSource").has("BACNET_IP.DS"))
                .isTrue();
        assertThat(service.schemas(TENANT_ID, DEVICE_A).at("/families/dataSource").has("MODBUS_IP.DS"))
                .isTrue();
        verify(access, times(2)).call(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void aFailedFetchIsNotCached() throws Exception {
        when(access.call(any(), eq(DEVICE_A), anyString(), anyString(), any(), any()))
                .thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> service.schemas(TENANT_ID, DEVICE_A)).isInstanceOf(Exception.class);

        // A gateway that was down when someone first opened a form must not stay unusable for half
        // an hour because the failure was remembered.
        when(access.call(any(), eq(DEVICE_A), anyString(), anyString(), any(), any()))
                .thenReturn(new GatewayResponse(200, DOCUMENT));
        assertThat(service.schemas(TENANT_ID, DEVICE_A).has("families")).isTrue();
    }

    @Test
    void aGatewayThatAnswersWithAnErrorStatusIsNotCachedEither() throws Exception {
        when(access.call(any(), eq(DEVICE_A), anyString(), anyString(), any(), any()))
                .thenReturn(new GatewayResponse(403, "{}"));

        assertThatThrownBy(() -> service.schemas(TENANT_ID, DEVICE_A))
                .hasMessageContaining("403");

        when(access.call(any(), eq(DEVICE_A), anyString(), anyString(), any(), any()))
                .thenReturn(new GatewayResponse(200, DOCUMENT));
        assertThat(service.schemas(TENANT_ID, DEVICE_A).has("families")).isTrue();
    }

    @Test
    void anUnparseableDocumentIsRefusedRatherThanCachedAsNull() throws Exception {
        when(access.call(any(), eq(DEVICE_A), anyString(), anyString(), any(), any()))
                .thenReturn(new GatewayResponse(200, "not json"));

        assertThatThrownBy(() -> service.schemas(TENANT_ID, DEVICE_A))
                .isInstanceOf(IOException.class);
    }

    @Test
    void forgettingOneGatewayLeavesTheOthersAlone() throws Exception {
        service.schemas(TENANT_ID, DEVICE_A);
        service.schemas(TENANT_ID, DEVICE_B);
        service.forget(DEVICE_A);

        service.schemas(TENANT_ID, DEVICE_A);
        service.schemas(TENANT_ID, DEVICE_B);
        // A re-adopted gateway may be different hardware on a different stack version, so its
        // document is dropped -- but nobody else's is.
        verify(access, times(3)).call(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void theSchemaRouteIsOnTheAllowlistAndReadableByACustomerUser() {
        // The forms are the feature. If the probe path were admin-only, every data source form
        // would 403 for a customer user and the tab would render as broken rather than read-only.
        assertThat(InferrixGatewayRoutes.isAllowed("GET", InferrixGatewaySchemaService.SCHEMAS_PATH))
                .isTrue();
        assertThat(InferrixGatewayRoutes.requiresTenantAdmin(
                "GET", InferrixGatewaySchemaService.SCHEMAS_PATH)).isFalse();
    }
}
