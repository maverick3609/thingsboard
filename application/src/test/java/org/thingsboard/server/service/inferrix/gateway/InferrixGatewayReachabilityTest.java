// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAccess.GatewayUnreachableException;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a gateway cannot be reached, which is the whole value of the reachability endpoint.
 *
 * <p>"Unreachable" on its own sends an operator to check cables when the real answer is that they
 * pasted the wrong secret, or that the token they issued is not an administrator. Each reason here
 * is produced by its own condition and nothing collapses two of them together.
 */
class InferrixGatewayReachabilityTest {

    @Test
    void aGatewayThatAnswersIsReachableAndReportsItsVersion() {
        InferrixGatewayReachability result = InferrixGatewayReachability.of(new GatewayResponse(200,
                "{\"version\":\"5.1.0\",\"schemaVersion\":\"42\",\"hostName\":\"gw-1\"}"));

        assertTrue(result.reachable());
        assertEquals("OK", result.reason());
        // The Details and Health tabs have no other identity source -- there is no version or
        // uptime endpoint besides /v2/about -- so the probe carries it rather than costing a
        // second call.
        assertEquals("5.1.0", result.stackVersion());
        assertThat(result.checkedAt()).isPositive();
    }

    @Test
    void aGatewayThatAnswersWithoutAVersionIsStillReachable() {
        // An older stack, or a body shape that changed. Reachability is about whether the platform
        // can talk to it, and it demonstrably can.
        InferrixGatewayReachability result =
                InferrixGatewayReachability.of(new GatewayResponse(200, "{}"));
        assertTrue(result.reachable());
        assertEquals("OK", result.reason());
        assertThat(result.stackVersion()).isNull();

        assertTrue(InferrixGatewayReachability.of(new GatewayResponse(200, "not json")).reachable());
    }

    @Test
    void aRejectedTokenIsNotTheSameAsAnUnreachableGateway() {
        // 401 means the network is fine and the credential is not: the operator rotated or revoked
        // the API token on the gateway. Telling them "unreachable" would send them to check the
        // network, which is the one thing that is working.
        InferrixGatewayReachability unauthorized =
                InferrixGatewayReachability.of(new GatewayResponse(401, ""));
        assertFalse(unauthorized.reachable());
        assertEquals("UNAUTHORIZED", unauthorized.reason());
        assertThat(unauthorized.message()).contains("token");
    }

    @Test
    void anUnderPrivilegedTokenIsItsOwnAnswer() {
        // The expected steady state until stack ask A10 lands: the service account is deliberately
        // non-admin (spec 2.9), so the platform-link routes answer 403 while everything else
        // works. It must not read as broken -- the UI greys that tab rather than the gateway.
        InferrixGatewayReachability forbidden =
                InferrixGatewayReachability.of(new GatewayResponse(403, ""));
        assertFalse(forbidden.reachable());
        assertEquals("FORBIDDEN", forbidden.reason());
        assertThat(forbidden.message()).contains("permission");
    }

    @Test
    void anyOtherStatusIsReportedAsWhatItWas() {
        InferrixGatewayReachability error =
                InferrixGatewayReachability.of(new GatewayResponse(500, "boom"));
        assertFalse(error.reachable());
        assertEquals("HTTP_ERROR", error.reason());
        // The number is the diagnostic. Swallowing it leaves an operator with nothing to search.
        assertThat(error.message()).contains("500");
    }

    @Test
    void eachConfigurationFailureKeepsItsOwnReason() {
        // These come from InferrixGatewayAccess.load() and each sends the operator somewhere
        // different: record an address, fix a malformed one, adopt the gateway.
        for (String reason : new String[]{"NO_ADDRESS", "BAD_ADDRESS", "NO_CREDENTIAL"}) {
            InferrixGatewayReachability result = InferrixGatewayReachability.of(
                    new GatewayUnreachableException(reason, "some detail"));
            assertFalse(result.reachable());
            assertEquals(reason, result.reason());
            assertEquals("some detail", result.message());
        }
    }

    @Test
    void aMissingSealingKeyIsAPlatformFaultNotAGatewayFault() {
        // Nothing is wrong with the gateway: inferrix.controller.credentials_key is unset on this
        // node, so no gateway credential can be opened at all. An operator told "unreachable"
        // would go and power-cycle a working device.
        InferrixGatewayReachability result = InferrixGatewayReachability.of(
                new IllegalStateException("inferrix.controller.credentials_key is not set"));
        assertFalse(result.reachable());
        assertEquals("NO_SEALING_KEY", result.reason());
    }

    @Test
    void aNetworkOrTlsFailureIsTheOneThatReallyIsUnreachable() {
        InferrixGatewayReachability result =
                InferrixGatewayReachability.of(new IOException("Connection refused"));
        assertFalse(result.reachable());
        assertEquals("UNREACHABLE", result.reason());
        assertThat(result.message()).contains("Connection refused");

        // A changed certificate arrives as an IOException too, and it is the one failure an
        // operator must not shrug off as a flaky network: it means the device is not the device
        // that was adopted.
        InferrixGatewayReachability pinned = InferrixGatewayReachability.of(
                new IOException("The controller's certificate fingerprint changed: expected a but got b"));
        assertEquals("CERTIFICATE_CHANGED", pinned.reason());
    }

    @Test
    void everyReasonIsDistinct() {
        // A classifier that returns the same reason for two different conditions is worse than no
        // classifier -- it looks like a diagnosis and is not one.
        assertThat(java.util.Set.of(
                InferrixGatewayReachability.of(new GatewayResponse(200, "{}")).reason(),
                InferrixGatewayReachability.of(new GatewayResponse(401, "")).reason(),
                InferrixGatewayReachability.of(new GatewayResponse(403, "")).reason(),
                InferrixGatewayReachability.of(new GatewayResponse(500, "")).reason(),
                InferrixGatewayReachability.of(new GatewayUnreachableException("NO_ADDRESS", "x")).reason(),
                InferrixGatewayReachability.of(new IllegalStateException("credentials_key")).reason(),
                InferrixGatewayReachability.of(new IOException("refused")).reason(),
                InferrixGatewayReachability.of(new IOException("fingerprint changed")).reason()))
                .hasSize(8);
    }
}
