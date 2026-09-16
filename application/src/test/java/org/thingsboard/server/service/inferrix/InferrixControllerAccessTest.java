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
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
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
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private static final String UID = "3330393530335116002a0045";
    private static final String NEW_PASSWORD = "a-new-owner-password";

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
    @SuppressWarnings("unchecked")
    void aRefreshThatWaitedOutAPasswordChangeLogsInWithTheNewPassword() throws Exception {
        // The call took its credentials before the change and met the 401 after it. Retrying the old
        // password would fail and feed the device's login throttle.
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(serverAttributes("192.168.1.150", "old-token", "ownership-password")),
                        Futures.immediateFuture(serverAttributes("192.168.1.150", "old-token", NEW_PASSWORD)));
        when(client.call(any(), any(), any(), any(), eq("old-token"), any())).thenReturn(response(401, ""));
        when(client.call(any(), any(), any(), any(), eq("new-token"), any())).thenReturn(response(200, "{}"));
        when(client.login("192.168.1.150", FINGERPRINT, NEW_PASSWORD)).thenReturn("new-token");

        assertEquals(200, access.call(TENANT_ID, DEVICE_ID, "GET", "/api/v1/health", null).statusCode());
        verify(client, never()).login(any(), any(), eq("ownership-password"));
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

    @Test
    void aPasswordChangeStoresTheNewPasswordBeforeMintingAToken() throws Exception {
        // The device revokes its token on the change, so the new password must be safe in storage
        // before anything else can go wrong: it is the only thing that recovers access.
        List<String> stored = recordStores();
        when(client.changePassword("192.168.1.150", FINGERPRINT, "ownership-password", NEW_PASSWORD))
                .thenReturn(200);
        when(client.login("192.168.1.150", FINGERPRINT, NEW_PASSWORD)).thenReturn("fresh-token");

        access.changePassword(TENANT_ID, DEVICE_ID, NEW_PASSWORD);

        assertThat(stored).containsExactly(
                InferrixAdoptionService.ATTR_PASSWORD + "=" + NEW_PASSWORD,
                InferrixAdoptionService.ATTR_TOKEN + "=fresh-token");
    }

    @Test
    void aStoredPasswordTheDeviceNoLongerAcceptsChangesNothing() throws Exception {
        when(client.changePassword(any(), any(), any(), any())).thenReturn(401);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> access.changePassword(TENANT_ID, DEVICE_ID, NEW_PASSWORD));

        assertThat(e).hasMessageContaining("re-adopt");
        verify(tsSubService, never()).saveAttributes(any(AttributesSaveRequest.class));
        verify(client, never()).login(any(), any(), any());
    }

    @Test
    void aPasswordOutsideTheFirmwarePolicyNeverReachesTheDevice() throws Exception {
        for (String refused : new String[]{null, "short", "x".repeat(65), "pässwörd-with-umlauts", "tab\there-password",
                "has-a-\"quote\"", "has-a-back\\slash"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> access.changePassword(TENANT_ID, DEVICE_ID, refused));
        }
        verify(client, never()).changePassword(any(), any(), any(), any());
    }

    @Test
    void aFailedLoginAfterTheChangeIsNotReportedAsAFailedChange() throws Exception {
        // The change landed and its password is stored; the next call refreshes the token from it.
        List<String> stored = recordStores();
        when(client.changePassword(any(), any(), any(), any())).thenReturn(200);
        when(client.login(any(), any(), any())).thenThrow(new IOException("throttled"));

        access.changePassword(TENANT_ID, DEVICE_ID, NEW_PASSWORD);

        assertThat(stored).containsExactly(InferrixAdoptionService.ATTR_PASSWORD + "=" + NEW_PASSWORD);
    }

    @Test
    void aPasswordThatCannotBeStoredTellsTheOperatorHowToRecover() throws Exception {
        doAnswer(invocation -> {
            AttributesSaveRequest request = invocation.getArgument(0);
            request.getCallback().onFailure(new IllegalStateException("database unavailable"));
            return null;
        }).when(tsSubService).saveAttributes(any(AttributesSaveRequest.class));
        when(client.changePassword(any(), any(), any(), any())).thenReturn(200);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> access.changePassword(TENANT_ID, DEVICE_ID, NEW_PASSWORD));

        assertThat(e).hasMessageContaining("by address with the new password");
    }

    @Test
    void attestationVerifiesOurOwnNonceAgainstThePinnedCertificate() throws Exception {
        KeyPair device = InferrixAttestationTest.keyPair();
        X509Certificate certificate = InferrixAttestationTest.certificate(device);
        ControllerResponse healthy = response(200, "{}");
        when(client.call(any(), any(), eq("GET"), eq("/api/v1/health"), any(), any())).thenReturn(healthy);
        when(client.attest(eq("192.168.1.150"), eq(FINGERPRINT), eq("old-token"), anyString()))
                .thenAnswer(invocation -> {
                    String signature = InferrixAttestationTest.sign(device, invocation.getArgument(3), UID);
                    return new InferrixControllerClient.AttestExchange(
                            response(200, "{\"uid\":\"" + UID + "\",\"sig\":\"" + signature + "\"}"), certificate);
                });

        InferrixControllerAccess.AttestationResult result = access.attest(TENANT_ID, DEVICE_ID);

        assertThat(result.verified()).isTrue();
        assertThat(result.uid()).isEqualTo(UID);
        assertThat(result.reason()).isNull();
    }

    @Test
    void aControllerOnTheSharedDevelopmentCertificateIsToldApart() throws Exception {
        ControllerResponse healthy = response(200, "{}");
        ControllerResponse noKey = response(503, "{\"error\":\"no_device_key\"}");
        when(client.call(any(), any(), eq("GET"), eq("/api/v1/health"), any(), any())).thenReturn(healthy);
        when(client.attest(any(), any(), any(), anyString()))
                .thenReturn(new InferrixControllerClient.AttestExchange(noKey, null));

        InferrixControllerAccess.AttestationResult result = access.attest(TENANT_ID, DEVICE_ID);

        assertThat(result.verified()).isFalse();
        assertThat(result.reason()).contains("development certificate");
    }

    private List<String> recordStores() {
        List<String> stored = new ArrayList<>();
        doAnswer(invocation -> {
            AttributesSaveRequest request = invocation.getArgument(0);
            request.getEntries().forEach(entry ->
                    stored.add(entry.getKey() + "=" + codec.decrypt(entry.getValueAsString())));
            request.getCallback().onSuccess(null);
            return null;
        }).when(tsSubService).saveAttributes(any(AttributesSaveRequest.class));
        return stored;
    }

    private void stubAttributes(String storedIp, String token, String password) {
        stubAttributes(storedIp, token, password, null);
    }

    private void stubAttributes(String storedIp, String token, String password, String reportedIp) {
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(serverAttributes(storedIp, token, password)));
        when(attributesService.find(any(), any(), eq(AttributeScope.CLIENT_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(
                        reportedIp == null ? List.of() : List.of(attribute("ip", reportedIp))));
    }

    private List<AttributeKvEntry> serverAttributes(String storedIp, String token, String password) {
        List<AttributeKvEntry> server = new ArrayList<>();
        server.add(attribute(InferrixAdoptionService.ATTR_UID, UID));
        server.add(attribute(InferrixAdoptionService.ATTR_IP, storedIp));
        server.add(attribute(InferrixAdoptionService.ATTR_CERT_FINGERPRINT, FINGERPRINT));
        server.add(attribute(InferrixAdoptionService.ATTR_TOKEN, codec.encrypt(token)));
        if (password != null) {
            server.add(attribute(InferrixAdoptionService.ATTR_PASSWORD, codec.encrypt(password)));
        }
        return server;
    }

    private static AttributeKvEntry attribute(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private static ControllerResponse response(int status, String body) {
        return new ControllerResponse(status, body);
    }

}
