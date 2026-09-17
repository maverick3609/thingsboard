// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * Checks a controller's attestation: that the key in its pinned certificate signed a nonce the
 * platform chose just now, bound to the silicon UID the platform adopted.
 *
 * <p>The format is the firmware's INTEGRATION-API.md §3.6, verified with openssl against the bench
 * board: {@code ECDSA-P256 over SHA-256("infx-attest-v1" || nonce_bytes || uid_ascii)}, DER encoded.
 *
 * <p>The message is rebuilt from the platform's own nonce and the UID recorded at adoption, never
 * from the values the device echoes back. A device that answered with a signature over some other
 * nonce or some other UID therefore fails verification rather than being checked against its own
 * claims.
 */
final class InferrixAttestation {

    static final String CONTEXT = "infx-attest-v1";

    private static final int NONCE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InferrixAttestation() {
    }

    static String newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    /**
     * @return {@code null} when the signature verifies, otherwise why it does not
     */
    static String check(JsonNode body, X509Certificate certificate, String nonceHex, String adoptedUid) {
        if (certificate == null) {
            return "No pinned certificate was captured for the exchange";
        }
        String reportedUid = body == null ? null : body.path("uid").asText(null);
        if (reportedUid != null && !reportedUid.equalsIgnoreCase(adoptedUid)) {
            return "The controller signed for UID " + reportedUid + ", not the adopted " + adoptedUid;
        }
        byte[] signature;
        try {
            signature = HexFormat.of().parseHex(body == null ? "" : body.path("sig").asText(""));
        } catch (IllegalArgumentException e) {
            return "The controller's signature is not hex";
        }
        if (signature.length == 0) {
            return "The controller returned no signature";
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(CONTEXT.getBytes(StandardCharsets.US_ASCII));
            verifier.update(HexFormat.of().parseHex(nonceHex));
            verifier.update(adoptedUid.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature) ? null
                    : "The signature does not verify against the pinned certificate";
        } catch (GeneralSecurityException e) {
            // A malformed DER signature lands here as well as a key of the wrong type.
            return "The signature could not be checked: " + e.getMessage();
        }
    }

}
