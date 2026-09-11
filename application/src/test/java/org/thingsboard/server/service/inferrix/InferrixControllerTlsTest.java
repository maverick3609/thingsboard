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

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.FingerprintCapturingTrustManager;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the TLS decision against a certificate shaped like the firmware's: EC P-256, self-signed,
 * and carrying <b>no X.509 extensions at all</b> — so no {@code subjectAltName}.
 *
 * <p>This is the case that took the whole device-facing REST surface down against real hardware. A
 * standard verifier rejects such a certificate with "No subject alternative names present" before
 * the fingerprint pin is ever consulted, so the handshake has to succeed on the pin alone — while
 * still failing closed when the pin does not match.
 */
class InferrixControllerTlsTest {

    private SSLServerSocket server;
    private ExecutorService accepting;
    private X509Certificate serverCertificate;

    @BeforeEach
    void startSanLessServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Inferrix Controller, O=Inferrix");
        // No extensions are added: this is exactly what the firmware serves.
        serverCertificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.ONE,
                        new Date(System.currentTimeMillis() - 86_400_000L),
                        new Date(System.currentTimeMillis() + 86_400_000L),
                        subject,
                        keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("device", keyPair.getPrivate(), new char[0],
                new java.security.cert.Certificate[]{serverCertificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, new char[0]);

        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(keyManagers.getKeyManagers(), null, null);
        server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());

        accepting = Executors.newSingleThreadExecutor();
        accepting.submit(() -> {
            while (!server.isClosed()) {
                try (Socket accepted = server.accept()) {
                    ((SSLSocket) accepted).startHandshake();
                    accepted.getInputStream().read();
                } catch (Exception ignored) {
                    // A client that fails the pin drops the connection; that is the case under test.
                }
            }
            return null;
        });
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        accepting.shutdownNow();
    }

    @Test
    void aCertificateWithNoSubjectAltNameStillCompletesTheHandshake() throws Exception {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);

        try (SSLSocket socket = upgrade(trust)) {
            socket.startHandshake();
            assertEquals(InferrixControllerClient.fingerprintOf(serverCertificate), trust.getFingerprint());
        }
    }

    @Test
    void aPinThatDoesNotMatchStillFailsClosed() throws Exception {
        // Same SAN-less certificate, but pinned to something else: turning hostname verification off
        // must not have turned the trust decision off with it.
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThrows(SSLHandshakeException.class, () -> {
            try (SSLSocket socket = upgrade(trust)) {
                socket.startHandshake();
            }
        });
    }

    private SSLSocket upgrade(FingerprintCapturingTrustManager trust) throws Exception {
        Socket plain = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        return InferrixControllerClient.tlsStrategy(trust)
                .upgrade(plain, "192.168.1.150", server.getLocalPort(), null, null);
    }

}
