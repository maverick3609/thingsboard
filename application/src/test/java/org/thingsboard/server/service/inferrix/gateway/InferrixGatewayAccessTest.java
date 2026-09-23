// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

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
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayToken;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InferrixGatewayAccessTest {

    private static final TenantId TENANT_ID = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE_ID = new DeviceId(UUID.randomUUID());
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String CLIENT_ID = "tok-0001";
    private static final String CLIENT_SECRET = "s3cr3t-value";
    private static final String FINGERPRINT = "aa:bb:cc";
    private static final String JWT = "jwt-one";

    @Mock
    private InferrixGatewayClient client;
    @Mock
    private AttributesService attributesService;

    private InferrixSecretCodec secretCodec;
    private InferrixGatewayAccess access;

    @BeforeEach
    void setUp() throws Exception {
        secretCodec = new InferrixSecretCodec(KEY);
        access = new InferrixGatewayAccess(client, secretCodec, attributesService);
        when(client.exchangeToken(anyString(), any(), eq(CLIENT_ID), eq(CLIENT_SECRET)))
                .thenReturn(new GatewayToken(JWT, 1800));
        when(client.call(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(200, "{}"));
    }

    // --- Token handling ---------------------------------------------------------------------

    @Test
    void theTokenIsExchangedOnceAndReusedWithinItsLifetime() throws Exception {
        stubAttributes("10.0.0.5", null);

        for (int i = 0; i < 5; i++) {
            access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);
        }

        // The gateway's login limiter is per-IP burst 5 / refill 1-per-minute, and the whole
        // platform is one IP. A cache miss per call is not a latency problem, it is a fleet-wide
        // lockout, so this assertion is about availability rather than tidiness.
        verify(client, times(1)).exchangeToken(anyString(), any(), eq(CLIENT_ID), eq(CLIENT_SECRET));
        verify(client, times(5)).call(anyString(), any(), anyString(), anyString(), any(),
                eq(JWT), any());
    }

    @Test
    void aRejectedTokenIsExchangedAgainExactlyOnce() throws Exception {
        stubAttributes("10.0.0.5", null);
        when(client.call(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(401, ""), new GatewayResponse(200, "{}"));

        GatewayResponse response = access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        assertEquals(200, response.statusCode());
        verify(client, times(2)).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aPersistently401GatewayDoesNotLoop() throws Exception {
        stubAttributes("10.0.0.5", null);
        when(client.call(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(401, ""));

        GatewayResponse response = access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        // Surface the 401 rather than retrying: a genuinely wrong client_secret must not turn into
        // an exchange loop against a limiter that refills one token per minute.
        assertEquals(401, response.statusCode());
        verify(client, times(2)).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    @Test
    void forbiddenIsNotTreatedAsABadCredential() throws Exception {
        stubAttributes("10.0.0.5", null);
        when(client.call(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(403, ""));

        GatewayResponse response = access.call(TENANT_ID, DEVICE_ID, "GET",
                "/v2/platform-integration/server-details", null, null);

        // 403 means the token is valid but under-privileged -- the expected answer for a non-admin
        // service account on the platform-link routes, which stack ask A5 did not widen. Exchanging
        // again would spend the login limiter to obtain an identically under-privileged token.
        assertEquals(403, response.statusCode());
        verify(client, times(1)).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theTokenIsNeverPersisted() throws Exception {
        stubAttributes("10.0.0.5", null);

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        // The controller persists its bearer token because the device keeps exactly one and a
        // second login revokes the first. The gateway has no such constraint, so the JWT stays in
        // memory: one less copy of a credential at rest, and nothing to go stale across a restart.
        verify(attributesService, never()).save(any(), any(), any(AttributeScope.class), any(List.class));
    }

    // --- Address resolution -----------------------------------------------------------------

    @Test
    void theOperatorAddressWinsOverTheOneTheGatewayReports() throws Exception {
        stubAttributes("10.0.0.5", "192.168.9.9");

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        // The gateway's own value cannot serve a NAT'd deployment and reports nothing at all for
        // loopback -- the stack says so itself -- so an operator override has to win.
        verify(client).call(eq("https://10.0.0.5:443"), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void theGatewayReportedAddressIsUsedWhenNoOperatorAddressIsSet() throws Exception {
        stubAttributes(null, "192.168.9.9");

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        verify(client).call(eq("https://192.168.9.9:443"), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void theReportedAddressIsABareHostNotAUrl() throws Exception {
        // Stack ask A3 reports a bare address: no scheme, no port. Composing it is our job, and a
        // stored port must be honoured rather than assumed to be 443.
        stubAttributes(null, "192.168.9.9", 8443);

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        verify(client).call(eq("https://192.168.9.9:8443"), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void withNoAddressAtAllTheCallFailsWithoutTouchingTheNetwork() throws Exception {
        stubAttributes(null, null);

        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class)
                .hasMessageContaining("NO_ADDRESS");

        verify(client, never()).call(anyString(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(client, never()).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    // --- Credential custody -----------------------------------------------------------------

    @Test
    void theSecretIsOpenedOnlyHereAndNeverLeavesAsPlaintext() throws Exception {
        stubAttributes("10.0.0.5", null);

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        // What is stored is ciphertext; what reaches the client is the opened value. If these were
        // ever equal the attribute would be holding a plaintext credential.
        String stored = secretCodec.encrypt(CLIENT_SECRET);
        assertThat(stored).isNotEqualTo(CLIENT_SECRET);
        verify(client).exchangeToken(anyString(), any(), eq(CLIENT_ID), eq(CLIENT_SECRET));
    }

    @Test
    void anUnconfiguredSealingKeyRefusesRatherThanFallingBackToPlaintext() throws Exception {
        InferrixGatewayAccess unsealed =
                new InferrixGatewayAccess(client, new InferrixSecretCodec(""), attributesService);
        stubAttributes("10.0.0.5", null);

        assertThatThrownBy(() -> unsealed.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(client, never()).call(anyString(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aGatewayWithNoCredentialIsNotDialled() throws Exception {
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(List.of(
                        strAttr(InferrixGatewayAccess.MANAGEMENT_ADDRESS, "10.0.0.5"))));
        when(attributesService.find(any(), any(), eq(AttributeScope.CLIENT_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(List.of()));

        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class);

        verify(client, never()).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    // --- The address cannot be used to forge a path -----------------------------------------

    @Test
    void anAddressCarryingAPathCannotSmuggleOnePastTheAllowlist() throws Exception {
        // The bypass this test exists for: gwManagementAddress is operator-set (anyone with
        // WRITE_ATTRIBUTES) and falls back to a value the *device itself* reports over MQTT. Left
        // unvalidated it composes to
        //   https://gw.local/rest/v2/script/eval#:443/rest/v2/about
        // which Java parses as host gw.local, path /rest/v2/script/eval -- the allowlist approves
        // /v2/about and the gateway receives the script-evaluation endpoint. Verified on the wire
        // before this guard existed.
        for (String forged : new String[]{
                "gw.local/rest/v2/script/eval#",
                "gw.local/rest/v2/api-tokens?",
                "gw.local/rest/v2/system-actions/db-utils/backup.zip?",
                "gw.local:8443",
                "user@gw.local",
                "gw local",
                "../etc"}) {
            stubAttributes(forged, null);
            assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                    .as("forged address %s", forged)
                    .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class)
                    .hasMessageContaining("BAD_ADDRESS");
        }
        verify(client, never()).call(anyString(), any(), anyString(), anyString(), any(), anyString(), any());
        verify(client, never()).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theDeviceReportedAddressIsValidatedToo() throws Exception {
        // The fallback is worse than the operator value, not better: a compromised gateway chooses
        // it, and exchangeToken composes the same way -- so an unvalidated fallback lets a device
        // pick where Cortex delivers its own client_secret.
        stubAttributes(null, "gw.local/rest/v2/file-stores/default/capture?");

        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class)
                .hasMessageContaining("BAD_ADDRESS");

        verify(client, never()).exchangeToken(anyString(), any(), anyString(), anyString());
    }

    @Test
    void ordinaryAddressesStillWork() throws Exception {
        // The guard must not be so strict that it rejects real deployments.
        for (String good : new String[]{"10.0.0.5", "gw.local", "gw-1.site.example.com", "[fe80::1]"}) {
            stubAttributes(good, null);
            access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);
            verify(client).call(eq("https://" + good + ":443"), any(), anyString(), anyString(),
                    any(), anyString(), any());
        }
    }

    @Test
    void anOutOfRangePortIsRefused() throws Exception {
        stubAttributes("10.0.0.5", null, 70000);

        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class)
                .hasMessageContaining("BAD_ADDRESS");
    }

    // --- The allowlist is enforced at the chokepoint ----------------------------------------

    @Test
    void aPathOutsideTheAllowlistNeverReachesTheDevice() throws Exception {
        stubAttributes("10.0.0.5", null);

        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "POST", "/v2/api-tokens", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // Enforced here, not only in the REST controller, because this is the single chokepoint
        // every device-bound call passes through. A future endpoint that forgets the check is then
        // still covered.
        verify(client, never()).call(anyString(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void theTokenEndpointIsReachableInternallyDespiteBeingExcludedFromTheProxy() throws Exception {
        stubAttributes("10.0.0.5", null);

        access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null);

        // /v2/auth/** is excluded from the allowlist precisely so a browser can never spend our
        // client_secret -- yet the exchange itself must still happen. It does, because it is its
        // own client method rather than a proxied call, which is the property worth pinning.
        verify(client, times(1)).exchangeToken(anyString(), any(), eq(CLIENT_ID), eq(CLIENT_SECRET));
        assertThat(InferrixGatewayRoutes.isAllowed("POST", "/v2/auth/oauth/token")).isFalse();
    }

    @Test
    void aGatewayWithNoPinnedCertificateIsRefusedRatherThanCalledUnpinned() throws Exception {
        stubAttributes("10.0.0.5", null);
        // Same shape minus the pin, which is what an attribute delete leaves behind.
        List<AttributeKvEntry> server = new ArrayList<>();
        server.add(strAttr(InferrixGatewayAccess.CLIENT_ID, secretCodec.encrypt(CLIENT_ID)));
        server.add(strAttr(InferrixGatewayAccess.CLIENT_SECRET, secretCodec.encrypt(CLIENT_SECRET)));
        server.add(strAttr(InferrixGatewayAccess.MANAGEMENT_ADDRESS, "10.0.0.5"));
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(server));

        // A null pin does not mean "pin nothing to check" -- FingerprintCapturingTrustManager only
        // compares when the expected value is non-null, so combined with the disabled hostname
        // verification this feature requires, it means ANY certificate from ANY host is accepted
        // silently. The platform would then post the decrypted client_secret to whatever
        // gwManagementAddress says, and both attributes are writable by anyone holding
        // WRITE_ATTRIBUTES on the device.
        assertThatThrownBy(() -> access.call(TENANT_ID, DEVICE_ID, "GET", "/v2/about", null, null))
                .isInstanceOf(InferrixGatewayAccess.GatewayUnreachableException.class)
                .hasMessageContaining("certificate");
        verify(client, never()).exchangeToken(anyString(), any(), anyString(), anyString());
        verify(client, never()).call(anyString(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void probeReportsWhyRatherThanJustWhether() throws Exception {
        stubAttributes("10.0.0.5", null);
        when(client.call(anyString(), any(), eq("GET"), eq("/v2/about"), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(200, "{\"stackVersion\":\"5.1.0\"}"));

        InferrixGatewayReachability ok = access.probe(TENANT_ID, DEVICE_ID);
        assertThat(ok.reachable()).isTrue();
        assertThat(ok.stackVersion()).isEqualTo("5.1.0");

        // A gateway with no address recorded has not been adopted -- that is a configuration
        // answer, not a network one, and the probe must not report it as a dead device.
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(List.of()));
        InferrixGatewayReachability none = access.probe(TENANT_ID, DEVICE_ID);
        assertThat(none.reachable()).isFalse();
        assertThat(none.reason()).isEqualTo("NO_ADDRESS");
    }

    @Test
    void probeNeverThrows() throws Exception {
        stubAttributes("10.0.0.5", null);
        when(client.call(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new java.io.IOException("Connection refused"));

        // The endpoint behind this exists to answer "can you reach it", so throwing would make the
        // one question it is asked unanswerable. Every failure becomes a reason instead.
        InferrixGatewayReachability result = access.probe(TENANT_ID, DEVICE_ID);
        assertThat(result.reachable()).isFalse();
        assertThat(result.reason()).isEqualTo("UNREACHABLE");
    }

    @Test
    void probeUsesAnAllowlistedRouteSoItCannotFailOnItsOwnDiagnostic() {
        assertThat(InferrixGatewayRoutes.isAllowed("GET", InferrixGatewayAccess.PROBE_PATH)).isTrue();
        // And a customer user must be able to run it -- the Details tab shows reachability, and a
        // probe that demanded tenant admin would render that tab broken for everyone else.
        assertThat(InferrixGatewayRoutes.requiresTenantAdmin("GET", InferrixGatewayAccess.PROBE_PATH))
                .isFalse();
    }

    // --- helpers ----------------------------------------------------------------------------

    private void stubAttributes(String operatorAddress, String reportedAddress) {
        stubAttributes(operatorAddress, reportedAddress, null);
    }

    private void stubAttributes(String operatorAddress, String reportedAddress, Integer port) {
        List<AttributeKvEntry> server = new ArrayList<>();
        server.add(strAttr(InferrixGatewayAccess.CLIENT_ID, secretCodec.encrypt(CLIENT_ID)));
        server.add(strAttr(InferrixGatewayAccess.CLIENT_SECRET, secretCodec.encrypt(CLIENT_SECRET)));
        server.add(strAttr(InferrixGatewayAccess.CERT_FINGERPRINT, FINGERPRINT));
        if (operatorAddress != null) {
            server.add(strAttr(InferrixGatewayAccess.MANAGEMENT_ADDRESS, operatorAddress));
        }
        if (port != null) {
            server.add(new BaseAttributeKvEntry(
                    new LongDataEntry(InferrixGatewayAccess.MANAGEMENT_PORT, port.longValue()),
                    System.currentTimeMillis()));
        }
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(server));
        when(attributesService.find(any(), any(), eq(AttributeScope.CLIENT_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(reportedAddress == null
                        ? List.of()
                        : List.of(strAttr(InferrixGatewayAccess.REPORTED_ADDRESS, reportedAddress))));
    }

    private static AttributeKvEntry strAttr(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }
}
