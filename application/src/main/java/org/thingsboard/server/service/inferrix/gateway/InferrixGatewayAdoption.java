// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.thingsboard.server.service.inferrix.InferrixSecretCodec;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules adoption applies, separated from the machinery that applies them.
 *
 * <p>Everything here is decidable from the operator's input and the gateway's answers — no device,
 * no database, no Spring context — which is what makes it testable. {@code InferrixGatewayAdoptionService}
 * does the parts that need those, and defers every judgement to this class.
 */
public final class InferrixGatewayAdoption {

    /**
     * SHA-256 of the leaf certificate that every Inferrix stack shipped before 5.1.0.
     *
     * <p>Not a development certificate: a real Sectigo-issued wildcard for {@code CN=*.inferrix.com},
     * whose private key was committed to the stack repository next to its password in
     * {@code env.properties}. Trust-on-first-use pins whatever a device presents, which is only
     * meaningful if that device is the only holder of the private key — and here anybody who cloned
     * the repository is. Pinning it would record a fingerprint that proves nothing.
     *
     * <p>The certificate expired on 2024-11-17 and 5.1.0 generates a per-gateway key pair at boot,
     * so a gateway still presenting this has not been upgraded. The right answer is to say so at
     * adoption rather than to adopt it and call the result secure.
     */
    private static final String LEAKED_DEFAULT_CERTIFICATE =
            "a068bddd974ee22244dde0ceb8b6e521d570fbbc9cff58b4b61dba3ea2578d0b";

    /**
     * Read-only, admin-only, and on the allowlist — the three properties the probe needs.
     *
     * <p>Whether the service account can reach the platform-link domain is a question only the
     * gateway can answer, so adoption asks it rather than reading a claim out of the token. Stack
     * ask A5 widened data source, publisher and event handler creation to
     * {@code permissionGatewayConfiguration}, but left every {@code /v2/platform-integration}
     * method on {@code isAdmin()} — so this one call distinguishes the two cases exactly.
     */
    public static final String ADMIN_PROBE_PATH = "/v2/platform-integration/device-profile";

    private static final int DEFAULT_PORT = 443;

    /** Mirrors {@link InferrixGatewayAccess}'s authority rule, applied at the point of entry. */
    private static final Pattern HOST = Pattern.compile(
            "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "|\\[[0-9A-Fa-f:]{2,45}\\]");

    private InferrixGatewayAdoption() {
    }

    /**
     * @param fingerprint a SHA-256 in any of the shapes tooling produces — upper or lower case,
     *                    with or without colons
     */
    public static boolean isKnownBadCertificate(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return LEAKED_DEFAULT_CERTIFICATE.equals(
                fingerprint.replace(":", "").trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Fails before anything is written. A gateway that is half-adopted is worse than one that
     * failed to adopt, because it looks configured.
     */
    public static void validate(AdoptRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No adoption request was supplied");
        }
        if (isBlank(request.address()) || !HOST.matcher(request.address()).matches()) {
            throw new IllegalArgumentException(
                    "The management address must be a bare host name or IP address, with no scheme,"
                            + " port or path");
        }
        if (request.port() != null && (request.port() < 1 || request.port() > 65535)) {
            throw new IllegalArgumentException("The management port must be between 1 and 65535");
        }
        // Cortex generates no secret for a gateway: the operator issues an API token there and
        // pastes both halves in, so a half-filled form is the likely mistake.
        if (isBlank(request.clientId())) {
            throw new IllegalArgumentException("The API token's client id is required");
        }
        if (isBlank(request.clientSecret())) {
            throw new IllegalArgumentException("The API token's client secret is required");
        }
    }

    public static int portOrDefault(Integer port) {
        return port == null ? DEFAULT_PORT : port;
    }

    public static void requireSealingKey(InferrixSecretCodec codec) {
        if (!codec.isConfigured()) {
            throw new IllegalStateException(
                    "inferrix.controller.credentials_key is not set, so a gateway credential cannot"
                            + " be sealed. Refusing to adopt rather than storing it in the clear.");
        }
    }

    /**
     * Reads adminness off the probe's status, and refuses to guess from anything else.
     *
     * <p>Recording "not an administrator" because the gateway answered 500 would tell an operator
     * their token is under-privileged when in fact the gateway is broken — and they would then go
     * and reissue a token that was never the problem.
     */
    public static boolean isAdminFrom(int probeStatus) {
        return switch (probeStatus) {
            case 200 -> true;
            case 403 -> false;
            default -> throw new IllegalStateException(
                    "The gateway answered " + probeStatus + " to the privilege probe, which says"
                            + " nothing about the token. Adoption cannot record what it did not learn.");
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * What an operator supplies to adopt a gateway.
     *
     * <p>Note what is absent: any secret Cortex invents. Stack ask A4 made the credential an API
     * token issued on the gateway, so both halves arrive from the operator and the platform's job
     * is to seal them, not to mint them.
     */
    public record AdoptRequest(String deviceName, String address, Integer port,
                               String clientId, String clientSecret,
                               Boolean acceptDifferentGateway) {

        /**
         * Whether the operator has said out loud that this is a different box under the same name.
         *
         * <p>Only ever consulted when the stored certificate disagrees with the served one. Kept
         * on the request rather than inferred, because the two cases it separates — replacing
         * failed hardware, and adopting the wrong gateway from a saved form — look identical to
         * the platform and only the operator can tell them apart.
         */
        public boolean confirmsDifferentGateway() {
            return Boolean.TRUE.equals(acceptDifferentGateway);
        }
    }
}
