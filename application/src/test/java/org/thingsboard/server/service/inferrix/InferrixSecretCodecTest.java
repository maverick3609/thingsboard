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

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferrixSecretCodecTest {

    private static final String KEY_A = key((byte) 1);
    private static final String KEY_B = key((byte) 2);

    @Test
    void sealedValuesRoundTrip() {
        InferrixSecretCodec codec = new InferrixSecretCodec(KEY_A);
        assertTrue(codec.isConfigured());
        String secret = "correct horse battery staple";
        assertEquals(secret, codec.decrypt(codec.encrypt(secret)));
    }

    @Test
    void theSamePlaintextSealsDifferentlyEachTime() {
        InferrixSecretCodec codec = new InferrixSecretCodec(KEY_A);
        // A fresh IV per message: two controllers sharing a password must not be visibly identical
        // to anyone reading the attributes table.
        assertNotEquals(codec.encrypt("same"), codec.encrypt("same"));
    }

    @Test
    void anotherKeyCannotOpenIt() {
        String sealed = new InferrixSecretCodec(KEY_A).encrypt("ownership password");
        assertThrows(IllegalStateException.class, () -> new InferrixSecretCodec(KEY_B).decrypt(sealed));
    }

    @Test
    void aTamperedValueIsRejectedRatherThanPartiallyDecrypted() {
        InferrixSecretCodec codec = new InferrixSecretCodec(KEY_A);
        byte[] blob = Base64.getDecoder().decode(codec.encrypt("ownership password"));
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);
        // GCM authenticates: a flipped bit fails the tag instead of yielding altered plaintext.
        assertThrows(IllegalStateException.class, () -> codec.decrypt(tampered));
    }

    @Test
    void withoutAKeyItRefusesToWorkRatherThanStoringPlaintext() {
        InferrixSecretCodec codec = new InferrixSecretCodec("  ");
        assertFalse(codec.isConfigured());
        assertThrows(IllegalStateException.class, () -> codec.encrypt("ownership password"));
    }

    @Test
    void aKeyOfTheWrongShapeFailsAtStartupNotAtFirstUse() {
        assertThrows(IllegalStateException.class, () -> new InferrixSecretCodec("not base64 at all!!"));
        assertThrows(IllegalStateException.class,
                () -> new InferrixSecretCodec(Base64.getEncoder().encodeToString(new byte[16])));
    }

    private static String key(byte fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, fill);
        return Base64.getEncoder().encodeToString(raw);
    }

}
