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

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attestation check against real P-256 keys and a certificate shaped like the firmware's, so
 * the message layout is exercised by an actual signature rather than asserted about.
 */
class InferrixAttestationTest {

    private static final String UID = "3330393530335116002a0045";

    @Test
    void aSignatureByThePinnedKeyOverOurNonceAndTheAdoptedUidVerifies() throws Exception {
        KeyPair device = keyPair();
        String nonce = InferrixAttestation.newNonce();

        String problem = InferrixAttestation.check(answer(UID, sign(device, nonce, UID)),
                certificate(device), nonce, UID);

        assertThat(problem).isNull();
    }

    @Test
    void aSignatureOverAnotherNonceIsRefused() throws Exception {
        // A replayed answer to an earlier challenge must not pass as an answer to this one.
        KeyPair device = keyPair();
        String earlier = InferrixAttestation.newNonce();

        String problem = InferrixAttestation.check(answer(UID, sign(device, earlier, UID)),
                certificate(device), InferrixAttestation.newNonce(), UID);

        assertThat(problem).contains("does not verify");
    }

    @Test
    void aKeyOtherThanThePinnedOneIsRefused() throws Exception {
        // A signature by any key but the pinned one must not verify.
        KeyPair pinned = keyPair();
        KeyPair impostor = keyPair();
        String nonce = InferrixAttestation.newNonce();

        String problem = InferrixAttestation.check(answer(UID, sign(impostor, nonce, UID)),
                certificate(pinned), nonce, UID);

        assertThat(problem).contains("does not verify");
    }

    @Test
    void aSignatureForAnotherUidIsRefusedEvenWhenItsOwnClaimIsConsistent() throws Exception {
        KeyPair device = keyPair();
        String nonce = InferrixAttestation.newNonce();
        String otherUid = "0123456789abcdef01234567";

        String problem = InferrixAttestation.check(answer(otherUid, sign(device, nonce, otherUid)),
                certificate(device), nonce, UID);

        assertThat(problem).contains(otherUid);
    }

    @Test
    void aSilentUidDoesNotLetAnotherUidsSignatureThrough() throws Exception {
        // The message is rebuilt from the adopted UID, not from what the device echoes, so leaving
        // the uid out of the answer cannot skip the binding.
        KeyPair device = keyPair();
        String nonce = InferrixAttestation.newNonce();

        String problem = InferrixAttestation.check(answer(null, sign(device, nonce, "0123456789abcdef01234567")),
                certificate(device), nonce, UID);

        assertThat(problem).contains("does not verify");
    }

    @Test
    void garbageInTheSignatureIsAnAnswerNotAnException() throws Exception {
        KeyPair device = keyPair();
        String nonce = InferrixAttestation.newNonce();

        assertThat(InferrixAttestation.check(answer(UID, "zz"), certificate(device), nonce, UID)).isNotNull();
        assertThat(InferrixAttestation.check(answer(UID, "3045022100"), certificate(device), nonce, UID)).isNotNull();
        assertThat(InferrixAttestation.check(answer(UID, ""), certificate(device), nonce, UID)).isNotNull();
        assertThat(InferrixAttestation.check(null, certificate(device), nonce, UID)).isNotNull();
    }

    @Test
    void withoutACapturedCertificateNothingVerifies() throws Exception {
        KeyPair device = keyPair();
        String nonce = InferrixAttestation.newNonce();

        assertThat(InferrixAttestation.check(answer(UID, sign(device, nonce, UID)), null, nonce, UID))
                .isNotNull();
    }

    private static JsonNode answer(String uid, String signatureHex) {
        var body = JacksonUtil.newObjectNode()
                .put("alg", "ECDSA-P256-SHA256")
                .put("ctx", InferrixAttestation.CONTEXT)
                .put("sig", signatureHex);
        if (uid != null) {
            body.put("uid", uid);
        }
        return body;
    }

    static String sign(KeyPair keyPair, String nonceHex, String uid) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(InferrixAttestation.CONTEXT.getBytes(StandardCharsets.US_ASCII));
        signer.update(HexFormat.of().parseHex(nonceHex));
        signer.update(uid.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(signer.sign());
    }

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static X509Certificate certificate(KeyPair keyPair) throws Exception {
        X500Principal subject = new X500Principal("CN=Inferrix Controller, O=Inferrix");
        return new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
                        new Date(System.currentTimeMillis() - 86_400_000L),
                        new Date(System.currentTimeMillis() + 86_400_000L),
                        subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate())));
    }

}
