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
package org.thingsboard.server.service.inferrix;

import com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.ControllerResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InferrixControllerAccessTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE_ID = new DeviceId(UUID.randomUUID());
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String FINGERPRINT = "abc123";

    @Mock
    private InferrixControllerClient client;
    @Mock
    private AttributesService attributesService;
    @Mock
    private TelemetrySubscriptionService tsSubService;

    private InferrixSecretCodec codec;
    private InferrixControllerAccess access;

    @BeforeEach
    void setUp() {
        codec = new InferrixSecretCodec(KEY);
        access = new InferrixControllerAccess(client, codec, attributesService, tsSubService);
        stubAttributes("192.168.1.150", "old-token", "ownership-password");
    }

    @Test
    void aWorkingTokenIsUsedAsIsWithNoLogin() throws Exception {
        ControllerResponse ok = response(200, "{}");
        when(client.call(eq("192.168.1.150"), eq(FINGERPRINT), eq("GET"), eq("/api/v1/health"),
                eq("old-token"), any())).thenReturn(ok);

        assertEquals(200, access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null).statusCode());
        verify(client, never()).login(anyString(), anyString(), anyString());
    }

    @Test
    void aRevokedTokenIsRefreshedFromTheSealedPasswordAndTheCallIsReplayedOnce() throws Exception {
        // Any login elsewhere revokes the platform's token; over REST that is a recoverable 401.
        ControllerResponse unauthorized = response(401, "");
        ControllerResponse ok = response(200, "{}");
        when(client.call(any(), any(), any(), any(), eq("old-token"), any())).thenReturn(unauthorized);
        when(client.call(any(), any(), any(), any(), eq("new-token"), any())).thenReturn(ok);
        when(client.login("192.168.1.150", FINGERPRINT, "ownership-password")).thenReturn("new-token");

        assertEquals(200, access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null).statusCode());
        verify(client).login("192.168.1.150", FINGERPRINT, "ownership-password");
        verify(tsSubService).saveAttributes(any());
    }

    @Test
    void aSecondFailureIsNotRetriedAgain() throws Exception {
        // One retry only: a genuinely wrong password must not turn into a login loop against the
        // firmware's doubling brute-force throttle.
        ControllerResponse unauthorized = response(401, "");
        when(client.call(any(), any(), any(), any(), anyString(), any())).thenReturn(unauthorized);
        when(client.login(any(), any(), any())).thenReturn("new-token");

        assertEquals(401, access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null).statusCode());
        verify(client).login(any(), any(), any());
    }

    @Test
    void withoutASealedPasswordItSaysSoRatherThanLoopingOrFailingObscurely() throws Exception {
        ControllerResponse unauthorized = response(401, "");
        stubAttributes("192.168.1.150", "old-token", null);
        when(client.call(any(), any(), any(), any(), any(), any())).thenReturn(unauthorized);

        IOException e = assertThrows(IOException.class,
                () -> access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null));
        org.assertj.core.api.Assertions.assertThat(e).hasMessageContaining("re-adopting");
    }

    @Test
    void theDeviceReportedAddressWinsSoADhcpMoveHeals() throws Exception {
        // The controller republishes its IP over MQTT when DHCP moves it. Trusting that is safe: the
        // certificate pin still has to match, so a wrong host fails the handshake rather than
        // receiving the token.
        ControllerResponse ok = response(200, "{}");
        stubAttributes("192.168.1.150", "old-token", "ownership-password", "192.168.1.201");
        when(client.call(eq("192.168.1.201"), any(), any(), any(), any(), any())).thenReturn(ok);

        assertEquals(200, access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null).statusCode());
        verify(client).call(eq("192.168.1.201"), eq(FINGERPRINT), any(), any(), any(), any());
    }

    @Test
    void aDeviceThatWasNeverAdoptedIsRejected() {
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(List.of()));
        assertThrows(IllegalStateException.class,
                () -> access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null));
    }

    private void stubAttributes(String storedIp, String token, String password) {
        stubAttributes(storedIp, token, password, null);
    }

    private void stubAttributes(String storedIp, String token, String password, String reportedIp) {
        List<AttributeKvEntry> server = new ArrayList<>();
        server.add(attribute(InferrixAdoptionService.ATTR_IP, storedIp));
        server.add(attribute(InferrixAdoptionService.ATTR_CERT_FINGERPRINT, FINGERPRINT));
        server.add(attribute(InferrixAdoptionService.ATTR_TOKEN, codec.encrypt(token)));
        if (password != null) {
            server.add(attribute(InferrixAdoptionService.ATTR_PASSWORD, codec.encrypt(password)));
        }
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(server));
        when(attributesService.find(any(), any(), eq(AttributeScope.CLIENT_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(
                        reportedIp == null ? List.of() : List.of(attribute("ip", reportedIp))));
    }

    private static AttributeKvEntry attribute(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private static ControllerResponse response(int status, String body) {
        return new ControllerResponse(status, body);
    }

}
