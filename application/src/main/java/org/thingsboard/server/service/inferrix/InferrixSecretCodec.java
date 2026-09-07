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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.util.TbCoreComponent;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts the two controller credentials the platform has to keep: the ownership password and the
 * bearer token.
 *
 * <p>Both end up in server-scope attributes, which live in the platform database in the clear and
 * are readable through the attributes API by anyone with tenant-admin rights. The ownership password
 * is the durable credential for a physical device in a building — it must not be sitting there in
 * plaintext, so it is sealed under a key that lives only in {@code thingsboard.yml}, outside the
 * database. That is the same trust boundary as the database password and the JWT signing key, and
 * the same pattern the licence key already uses on this deployment.
 *
 * <p>Adoption <b>refuses to run</b> when no key is configured rather than falling back to storing
 * the credentials in the clear. A missing key is a deployment step the operator has not done yet;
 * silently downgrading the protection would hide that forever.
 *
 * <p>AES-256-GCM, fresh 12-byte IV per message, output is {@code base64(iv || ciphertext||tag)}.
 */
@Component
@TbCoreComponent
@Slf4j
public class InferrixSecretCodec {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    private SecretKeySpec key;

    public InferrixSecretCodec(@Value("${inferrix.controller.credentials_key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.info("No inferrix.controller.credentials_key configured; controller adoption is unavailable");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("inferrix.controller.credentials_key must be base64", e);
        }
        if (raw.length != KEY_LENGTH) {
            Arrays.fill(raw, (byte) 0);
            throw new IllegalStateException(
                    "inferrix.controller.credentials_key must decode to " + KEY_LENGTH + " bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to seal an Inferrix controller credential", e);
        }
    }

    public String decrypt(String sealed) {
        requireKey();
        try {
            byte[] blob = Base64.getDecoder().decode(sealed);
            if (blob.length <= IV_LENGTH) {
                throw new IllegalArgumentException("sealed value is too short");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH));
            byte[] plaintext = cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key, or a tampered value. Deliberately not logging the input.
            throw new IllegalStateException("Failed to open an Inferrix controller credential", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "inferrix.controller.credentials_key is not set; refusing to handle controller "
                            + "credentials without it. Generate one with: openssl rand -base64 32");
        }
    }

}
