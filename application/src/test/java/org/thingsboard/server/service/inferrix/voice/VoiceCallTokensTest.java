// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceCallTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final VoiceCallTokens tokens = new VoiceCallTokens(SECRET, 3600);

    private static VoiceCallClaims claims(String language) {
        return VoiceCallClaims.of(TenantId.fromUUID(UUID.randomUUID()), new NotificationRequestId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()), new AlarmId(UUID.randomUUID()), language, false);
    }

    private static void assertRefused(VoiceCallTokens tokens, String token) {
        assertThatThrownBy(() -> tokens.verify(token))
                .isInstanceOf(ThingsboardException.class)
                .hasMessage("Invalid voice call token")
                .hasNoCause()
                .extracting(e -> ((ThingsboardException) e).getErrorCode())
                .isEqualTo(ThingsboardErrorCode.AUTHENTICATION);
    }

    /** Signs a payload the way the format above says, independently of the class under test. */
    private static String signed(String json) throws Exception {
        String payload = B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return payload + "." + B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void roundTrip() throws Exception {
        VoiceCallClaims claims = claims("hi");

        VoiceCallClaims verified = tokens.verify(tokens.mint(claims));

        assertThat(verified.withExpiry(0)).isEqualTo(claims);
        assertThat(verified.expiresAt()).isGreaterThan(System.currentTimeMillis());
        assertThat(tokens.isExpired(verified, 0)).isFalse();
    }

    @Test
    void aTokenInTheDocumentedFormatVerifies() throws Exception {
        VoiceCallClaims verified = tokens.verify(signed("{\"v\":1,\"tid\":\"8b0e2f4e-6f53-4c1b-9d0e-1b2c3d4e5f60\","
                + "\"uid\":\"8b0e2f4e-6f53-4c1b-9d0e-1b2c3d4e5f61\",\"lang\":\"hi\",\"exp\":1}"));

        assertThat(verified.language()).isEqualTo("hi");
        assertThat(verified.alarmId()).isNull();
        assertThat(tokens.isExpired(verified, 0)).isTrue();
    }

    @Test
    void aSignatureFromAnotherPayloadIsRefused() {
        String first = tokens.mint(claims("en"));
        String second = tokens.mint(claims("en"));

        assertRefused(tokens, first.substring(0, first.indexOf('.')) + second.substring(second.indexOf('.')));
    }

    @Test
    void anotherSecretIsRefused() {
        VoiceCallTokens other = new VoiceCallTokens("another-secret-another-secret-123", 3600);

        assertRefused(tokens, other.mint(claims("en")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "a.b.c", ".", "e30.", "!!!.???", "e30.AAAA"})
    void malformedTokensAreRefused(String token) {
        assertRefused(tokens, token);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"v\":2,\"tid\":\"8b0e2f4e-6f53-4c1b-9d0e-1b2c3d4e5f60\",\"uid\":\"8b0e2f4e-6f53-4c1b-9d0e-1b2c3d4e5f61\"}",
            "{\"v\":1}"})
    void aSignedPayloadThatIsNotAClaimIsRefused(String json) throws Exception {
        assertRefused(tokens, signed(json));
    }

    @Test
    void anOverlongTokenIsRefused() {
        String token = tokens.mint(claims("x".repeat(VoiceCallTokens.MAX_TOKEN_LENGTH)));

        assertThat(token.length()).isGreaterThan(VoiceCallTokens.MAX_TOKEN_LENGTH);
        assertRefused(tokens, token);
    }

    @Test
    void anExpiredTokenStillVerifiesAndReportsExpiry() throws Exception {
        VoiceCallClaims verified = tokens.verify(new VoiceCallTokens(SECRET, -60).mint(claims("en")));

        assertThat(tokens.isExpired(verified, 0)).isTrue();
        assertThat(tokens.isExpired(verified, 120_000)).isFalse();
    }

    @Test
    void anUnsetOrShortSecretMintsAndAcceptsNothing() {
        String valid = tokens.mint(claims("en"));
        for (String secret : new String[]{"", "short-secret", null}) {
            VoiceCallTokens unconfigured = new VoiceCallTokens(secret, 3600);
            assertThat(unconfigured.isConfigured()).isFalse();
            assertThatThrownBy(() -> unconfigured.mint(claims("en"))).isInstanceOf(IllegalStateException.class);
            assertRefused(unconfigured, valid);
        }
    }

}
