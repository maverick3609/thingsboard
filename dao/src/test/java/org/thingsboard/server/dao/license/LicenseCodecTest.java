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
package org.thingsboard.server.dao.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LicenseCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode vectors;
    private LicenseCodec codec;
    private String goldenKey;

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/license/license-vectors.json")) {
            vectors = MAPPER.readTree(in);
        }
        codec = new LicenseCodec(vectors.get("publicKeyBase64").asText());
        goldenKey = vectors.get("expectedKey").asText();
    }

    @Test
    public void goldenVectorDecodes() {
        LicensePayload payload = codec.decodeAndVerify(goldenKey);
        JsonNode expected = vectors.get("payload");
        assertThat(payload.version()).isEqualTo(expected.get("v").asInt());
        assertThat(payload.iid()).isEqualTo(expected.get("iid").asText());
        assertThat(payload.cust()).isEqualTo(expected.get("cust").asText());
        assertThat(payload.exp()).isEqualTo(expected.get("exp").asLong());
        assertThat(payload.expMillis()).isEqualTo(expected.get("exp").asLong() * 1000L);
        assertThat(payload.cap("maxDevices")).isEqualTo(5000L);
        assertThat(payload.cap("maxAssets")).isEqualTo(2000L);
    }

    @Test
    public void absentCapIsUnlimited() {
        assertThat(codec.decodeAndVerify(goldenKey).cap("maxWidgets")).isNull();
    }

    @Test
    public void tamperedPayloadFails() {
        char[] chars = goldenKey.toCharArray();
        int i = 5;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> codec.decodeAndVerify(new String(chars)))
                .isInstanceOf(LicenseException.class)
                .extracting("violation")
                .isIn(LicenseViolation.BAD_SIGNATURE, LicenseViolation.MALFORMED);
    }

    @Test
    public void tamperedSignatureFails() {
        int dot = goldenKey.indexOf('.');
        char[] chars = goldenKey.toCharArray();
        chars[dot + 3] = chars[dot + 3] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> codec.decodeAndVerify(new String(chars)))
                .isInstanceOf(LicenseException.class)
                .hasFieldOrPropertyWithValue("violation", LicenseViolation.BAD_SIGNATURE);
    }

    @Test
    public void structurallyBrokenKeysAreMalformed() {
        for (String bad : new String[]{"", "nodot", "a.b.c", ".abc", "abc.", "!!!.???"}) {
            assertThatThrownBy(() -> codec.decodeAndVerify(bad))
                    .describedAs("input %s", bad)
                    .isInstanceOf(LicenseException.class)
                    .hasFieldOrPropertyWithValue("violation", LicenseViolation.MALFORMED);
        }
    }

    @Test
    public void nullKeyIsMalformed() {
        assertThatThrownBy(() -> codec.decodeAndVerify(null))
                .isInstanceOf(LicenseException.class)
                .hasFieldOrPropertyWithValue("violation", LicenseViolation.MALFORMED);
    }

    // Payload-rule tests go through parsePayload directly, NOT through decodeAndVerify. decodeAndVerify
    // verifies the signature BEFORE it parses, so a hand-built payload can only ever come back
    // BAD_SIGNATURE there. That ordering is deliberate — never parse attacker-controlled JSON before
    // authenticating it — so the tests bend to the code, not the other way round.

    @Test
    public void unknownVersionIsMalformed() {
        assertThatThrownBy(() -> LicenseCodec.parsePayload(
                "{\"v\":99,\"iid\":\"aa\",\"exp\":1}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(LicenseException.class)
                .hasFieldOrPropertyWithValue("violation", LicenseViolation.MALFORMED);
    }

    @Test
    public void missingRequiredFieldsAreMalformed() {
        String[] bad = {
                "{\"v\":1,\"exp\":1}",                         // no iid
                "{\"v\":1,\"iid\":\"  \",\"exp\":1}",              // blank iid
                "{\"v\":1,\"iid\":\"aa\"}",                       // no exp
                "{\"v\":1,\"iid\":\"aa\",\"exp\":\"soon\"}",         // exp not an integer
                "[]",                                          // not an object
                "not json at all"
        };
        for (String json : bad) {
            assertThatThrownBy(() -> LicenseCodec.parsePayload(json.getBytes(StandardCharsets.UTF_8)))
                    .describedAs("payload %s", json)
                    .isInstanceOf(LicenseException.class)
                    .hasFieldOrPropertyWithValue("violation", LicenseViolation.MALFORMED);
        }
    }

    @Test
    public void malformedCapIsMalformed() {
        String[] bad = {
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":{\"maxDevices\":-1}}",
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":{\"maxDevices\":1.5}}",
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":{\"maxDevices\":\"x\"}}",
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":{\"maxAssets\":-7}}",
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":[]}"
        };
        for (String json : bad) {
            assertThatThrownBy(() -> LicenseCodec.parsePayload(json.getBytes(StandardCharsets.UTF_8)))
                    .describedAs("payload %s", json)
                    .isInstanceOf(LicenseException.class)
                    .hasFieldOrPropertyWithValue("violation", LicenseViolation.MALFORMED);
        }
    }

    @Test
    public void zeroCapIsValidAndMeansZero() {
        LicensePayload payload = LicenseCodec.parsePayload(
                "{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"plan\":{\"maxDevices\":0}}".getBytes(StandardCharsets.UTF_8));
        assertThat(payload.cap("maxDevices")).isEqualTo(0L);
    }

    @Test
    public void handBuiltPayloadWithTheGoldenSignatureIsBadSignature() {
        // Proves the ordering above: swapping the payload while keeping a valid-looking signature is
        // caught as BAD_SIGNATURE, and parsePayload is never reached.
        String segment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":1,\"iid\":\"aa\",\"exp\":1}".getBytes(StandardCharsets.UTF_8));
        String forged = segment + "." + goldenKey.substring(goldenKey.indexOf('.') + 1);
        assertThatThrownBy(() -> codec.decodeAndVerify(forged))
                .isInstanceOf(LicenseException.class)
                .hasFieldOrPropertyWithValue("violation", LicenseViolation.BAD_SIGNATURE);
    }

    @Test
    public void unknownFieldsAreIgnored() {
        LicensePayload payload = LicenseCodec.parsePayload(
                ("{\"v\":1,\"iid\":\"aa\",\"exp\":1,\"futureField\":true,"
                        + "\"plan\":{\"maxDevices\":5,\"whiteLabeling\":true}}").getBytes(StandardCharsets.UTF_8));
        assertThat(payload.cap("maxDevices")).isEqualTo(5L);
    }

    @Test
    public void sha3HexIsSixtyFourLowercaseHexChars() {
        String hex = LicenseCodec.sha3Hex("9f3a1c02-4b6d-4c3e-9a10-2f7c5d3e8b71");
        assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    public void goldenVectorIidMatchesItsUuid() {
        // The single most important cross-project assertion: the generator hashed this UUID with its own
        // sha3Hex, and the platform must arrive at the same 64 hex chars with an independent implementation.
        assertThat(LicenseCodec.sha3Hex(vectors.get("instanceUuid").asText()))
                .isEqualTo(vectors.get("payload").get("iid").asText());
    }

    @Test
    public void sha3HexMatchesTheNistEmptyStringVector() {
        assertThat(LicenseCodec.sha3Hex(""))
                .isEqualTo("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a");
    }
}
