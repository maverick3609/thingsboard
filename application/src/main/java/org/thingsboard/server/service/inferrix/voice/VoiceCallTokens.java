// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Signs and checks call tokens, {@code base64url(json) "." base64url(hmacSha256)}. A token is the
 * only credential on the dialer's callbacks, which have no session, and only Cortex can make one.
 * Expiry is reported, not enforced, because the callers differ: precheck answers "expired", while a
 * call's result is still accepted a little late (see VoiceCallbackService). A secret that is blank,
 * shorter than 32 characters or equal to the dialer's bearer token leaves voice unconfigured, because
 * the dialer runs on this host and must not be able to mint tokens.
 */
@Component
@Slf4j
public class VoiceCallTokens {

    static final int MIN_SECRET_LENGTH = 32;
    static final int MAX_TOKEN_LENGTH = 4096;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlMs;

    /** Spring's constructor: it also takes the dialer's token, to refuse a secret equal to it. */
    @Autowired
    public VoiceCallTokens(@Value("${inferrix.voice.token_secret:}") String secret,
                           @Value("${inferrix.voice.token_ttl_sec:3600}") long ttlSec,
                           @Value("${inferrix.voice.dialer_token:}") String dialerToken) {
        this(blankIfDialerToken(secret, dialerToken), ttlSec);
    }

    public VoiceCallTokens(String secret, long ttlSec) {
        this.secret = secret != null && secret.length() >= MIN_SECRET_LENGTH && !secret.isBlank()
                ? secret.getBytes(StandardCharsets.UTF_8) : null;
        this.ttlMs = ttlSec * 1000;
    }

    private static String blankIfDialerToken(String secret, String dialerToken) {
        if (secret == null || secret.isBlank() || !secret.equals(dialerToken)) {
            return secret;
        }
        log.warn("inferrix.voice.token_secret is the same as inferrix.voice.dialer_token, so voice stays unconfigured; "
                + "give each its own value");
        return "";
    }

    public boolean isConfigured() {
        return secret != null;
    }

    public String mint(VoiceCallClaims claims) {
        if (secret == null) {
            throw new IllegalStateException("Voice call token secret is not configured");
        }
        VoiceCallClaims stamped = claims.withExpiry(System.currentTimeMillis() + ttlMs);
        String payload = ENCODER.encodeToString(JacksonUtil.toString(stamped).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    public VoiceCallClaims verify(String token) throws ThingsboardException {
        if (secret == null || token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw refused();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            throw refused();
        }
        String payload = token.substring(0, dot);
        byte[] expected = sign(payload).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
            throw refused();
        }
        VoiceCallClaims claims;
        try {
            claims = JacksonUtil.fromBytes(DECODER.decode(payload), VoiceCallClaims.class);
        } catch (IllegalArgumentException e) {
            throw refused(); // not chained: JacksonUtil's message echoes the payload bytes
        }
        if (claims == null || claims.version() != VoiceCallClaims.VERSION || claims.tenantId() == null || claims.userId() == null) {
            throw refused();
        }
        return claims;
    }

    public boolean isExpired(VoiceCallClaims claims, long graceMs) {
        return System.currentTimeMillis() > claims.expiresAt() + graceMs;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ThingsboardException refused() {
        return new ThingsboardException("Invalid voice call token", ThingsboardErrorCode.AUTHENTICATION);
    }

}
