// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adoption rules that are decidable without a device or a database.
 *
 * <p>Kept as pure functions on {@link InferrixGatewayAdoption} for the same reason the frontend
 * keeps its logic in the models layer: the parts worth testing should not require the parts that
 * are awkward to construct.
 */
class InferrixGatewayAdoptionTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // --- The known-bad certificate ----------------------------------------------------------

    @Test
    void aGatewayServingTheLeakedWildcardCertificateIsRefused() {
        // Until stack 5.1.0 every deployment shipped the same core/ssl/keystore.jks -- and it was
        // not a development certificate but a real Sectigo wildcard for *.inferrix.com whose
        // private key sat in git next to its password. Pinning that proves nothing: anyone who
        // cloned the repository can present it. A gateway still serving it has not been upgraded,
        // and must be fixed rather than trusted-on-first-use.
        assertTrue(InferrixGatewayAdoption.isKnownBadCertificate(
                "a068bddd974ee22244dde0ceb8b6e521d570fbbc9cff58b4b61dba3ea2578d0b"));
        // Case and separators vary by whoever formatted the fingerprint; the comparison must not.
        assertTrue(InferrixGatewayAdoption.isKnownBadCertificate(
                "A068BDDD974EE22244DDE0CEB8B6E521D570FBBC9CFF58B4B61DBA3EA2578D0B"));
        assertTrue(InferrixGatewayAdoption.isKnownBadCertificate(
                "A0:68:BD:DD:97:4E:E2:22:44:DD:E0:CE:B8:B6:E5:21:"
                        + "D5:70:FB:BC:9C:FF:58:B4:B6:1D:BA:3E:A2:57:8D:0B"));
    }

    @Test
    void anOrdinaryPerGatewayCertificateIsAccepted() {
        assertFalse(InferrixGatewayAdoption.isKnownBadCertificate("0".repeat(64)));
        assertFalse(InferrixGatewayAdoption.isKnownBadCertificate("deadbeef"));
        assertFalse(InferrixGatewayAdoption.isKnownBadCertificate(null));
        assertFalse(InferrixGatewayAdoption.isKnownBadCertificate(""));
    }

    // --- What adoption requires -------------------------------------------------------------

    @Test
    void anAdoptRequestMustCarryBothHalvesOfTheCredential() {
        // Cortex generates no secret for a gateway -- the operator issues an API token there and
        // pastes the pair in -- so a half-filled form is the likely mistake, and it must fail
        // before anything is written rather than produce a gateway that looks adopted.
        assertThatThrownBy(() -> InferrixGatewayAdoption.validate(
                new InferrixGatewayAdoption.AdoptRequest("gw-1", "10.0.0.5", 443, null, "s", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client id");
        assertThatThrownBy(() -> InferrixGatewayAdoption.validate(
                new InferrixGatewayAdoption.AdoptRequest("gw-1", "10.0.0.5", 443, "id", "  ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client secret");
    }

    @Test
    void anAdoptRequestMustCarryAUsableAddress() {
        // The same authority validation the call path applies, moved to the point of entry so the
        // operator is told at adoption rather than discovering it as a broken tab later. A host
        // carrying a path is the injection that bypassed the allowlist entirely.
        for (String bad : new String[]{null, "", "gw.local/rest/v2/script/eval#",
                "gw.local:8443", "user@gw.local", "gw local"}) {
            assertThatThrownBy(() -> InferrixGatewayAdoption.validate(
                    new InferrixGatewayAdoption.AdoptRequest("gw-1", bad, 443, "id", "secret", null)))
                    .as("address %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String good : new String[]{"10.0.0.5", "gw.local", "gw-1.site.example.com", "[fe80::1]"}) {
            InferrixGatewayAdoption.validate(
                    new InferrixGatewayAdoption.AdoptRequest("gw-1", good, 443, "id", "secret", null));
        }
    }

    @Test
    void thePortMustBeAPort() {
        for (Integer bad : new Integer[]{0, -1, 65536, 70000}) {
            assertThatThrownBy(() -> InferrixGatewayAdoption.validate(
                    new InferrixGatewayAdoption.AdoptRequest("gw-1", "10.0.0.5", bad, "id", "s", null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Absent means the default, which is a normal thing for an operator to leave blank.
        assertEquals(443, InferrixGatewayAdoption.portOrDefault(null));
        assertEquals(8443, InferrixGatewayAdoption.portOrDefault(8443));
    }

    // --- Credential custody -----------------------------------------------------------------

    @Test
    void theCredentialIsSealedAndNeverStoredAsTyped() {
        InferrixSecretCodec codec = new InferrixSecretCodec(KEY);
        String sealed = codec.encrypt("s3cr3t-value");

        assertThat(sealed).isNotEqualTo("s3cr3t-value");
        assertThat(sealed).doesNotContain("s3cr3t");
        assertEquals("s3cr3t-value", codec.decrypt(sealed));
    }

    @Test
    void adoptionIsRefusedOutrightWhenNoSealingKeyIsConfigured() {
        // Never a fallback to plaintext. An operator who has not set the key should be told, not
        // quietly given a platform that stores gateway credentials in the clear.
        assertThatThrownBy(() -> InferrixGatewayAdoption.requireSealingKey(new InferrixSecretCodec("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials_key");

        InferrixGatewayAdoption.requireSealingKey(new InferrixSecretCodec(KEY));
    }

    // --- What the token can reach -----------------------------------------------------------

    @Test
    void anAdminProbeIsReadOnlyAndOnTheAllowlist() {
        // Whether the service account can reach the platform-link domain is established by asking
        // the gateway, not by trusting a claim inside the token: the question is what it can do,
        // and only the gateway can answer that. The probe must be a harmless GET and must itself
        // be forwardable, or adoption would fail on its own diagnostic.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", InferrixGatewayAdoption.ADMIN_PROBE_PATH));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", InferrixGatewayAdoption.ADMIN_PROBE_PATH));
    }

    @Test
    void theProbeReadsAdminnessFromTheStatusCode() {
        assertTrue(InferrixGatewayAdoption.isAdminFrom(200));
        assertFalse(InferrixGatewayAdoption.isAdminFrom(403));
        // Anything else is not an answer. Recording "not admin" on a 500 would tell the operator
        // their token is under-privileged when the gateway simply broke.
        assertThatThrownBy(() -> InferrixGatewayAdoption.isAdminFrom(500))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InferrixGatewayAdoption.isAdminFrom(401))
                .isInstanceOf(IllegalStateException.class);
    }
}
