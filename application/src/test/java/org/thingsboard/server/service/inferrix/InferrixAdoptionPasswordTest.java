// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.thingsboard.server.service.inferrix.InferrixAdoptionService.DEVICE_MQTT_PASSWORD_MAX_CHARS;
import static org.thingsboard.server.service.inferrix.InferrixAdoptionService.newPassword;

/**
 * The generated password has to fit the field it is written into.
 *
 * <p>It did not: 24 random bytes base64url-encode to exactly 32 characters and the firmware's
 * {@code settings_mqtt.password} takes 31, so {@code PUT /api/v1/mqtt} answered 400 and every
 * adoption failed at the last step — after the device had already been registered. One character.
 */
class InferrixAdoptionPasswordTest {

    @Test
    void aGeneratedPasswordFitsTheDeviceField() {
        for (int i = 0; i < 100; i++) {
            String password = newPassword();
            assertTrue(password.length() <= DEVICE_MQTT_PASSWORD_MAX_CHARS,
                    "generated a " + password.length() + "-character password, but the device takes "
                            + DEVICE_MQTT_PASSWORD_MAX_CHARS);
        }
    }

    @Test
    void aGeneratedPasswordIsUrlSafeAndUnpadded() {
        // It travels inside a JSON body the firmware parses with a fixed charset, and '+' and '/'
        // from standard base64 are exactly what a stricter parser would reject.
        for (int i = 0; i < 100; i++) {
            assertTrue(newPassword().matches("[A-Za-z0-9_-]+"), "expected url-safe base64 without padding");
        }
    }

    @Test
    void generatedPasswordsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(newPassword()), "the generator repeated a password");
        }
    }

}
