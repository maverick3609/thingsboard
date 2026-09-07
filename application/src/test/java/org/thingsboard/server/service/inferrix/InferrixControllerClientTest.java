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

import org.junit.jupiter.api.Test;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.FingerprintCapturingTrustManager;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the trust decision, which is the whole of the security model for talking to a controller:
 * there is no CA behind the device's self-signed certificate, so the SHA-256 fingerprint is all the
 * platform has to go on.
 */
class InferrixControllerClientTest {

    /** SHA-256 of the three bytes {1, 2, 3}. */
    private static final String FINGERPRINT_OF_123 =
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";

    @Test
    void theFingerprintIsTheSha256OfTheEncodedCertificate() throws Exception {
        assertEquals(FINGERPRINT_OF_123, InferrixControllerClient.fingerprintOf(cert(1, 2, 3)));
    }

    @Test
    void firstContactAcceptsAnyCertificateAndRemembersIt() throws Exception {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);
        trust.checkServerTrusted(new X509Certificate[]{cert(1, 2, 3)}, "RSA");
        assertEquals(FINGERPRINT_OF_123, trust.getFingerprint());
    }

    @Test
    void aPinnedSessionRefusesADifferentCertificate() throws Exception {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(FINGERPRINT_OF_123);
        // Same device address, different key: either the device was reflashed or something else is
        // answering for it. Both must fail closed rather than hand over a bearer token.
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[]{cert(9, 9, 9)}, "RSA"));
    }

    @Test
    void aPinnedSessionAcceptsTheSameCertificate() throws Exception {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(FINGERPRINT_OF_123);
        trust.checkServerTrusted(new X509Certificate[]{cert(1, 2, 3)}, "RSA");
        assertEquals(FINGERPRINT_OF_123, trust.getFingerprint());
    }

    @Test
    void anEmptyChainIsRefused() {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);
        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(null, "RSA"));
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void itNeverActsAsAServerSideTrustManager() {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);
        assertThrows(CertificateException.class,
                () -> trust.checkClientTrusted(new X509Certificate[0], "RSA"));
        assertEquals(0, trust.getAcceptedIssuers().length);
    }

    private static X509Certificate cert(int... encoded) throws Exception {
        byte[] der = new byte[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            der[i] = (byte) encoded[i];
        }
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(der);
        return certificate;
    }

}
