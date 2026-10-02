// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.notification.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.settings.UserNotificationSettings;
import org.thingsboard.server.common.data.notification.targets.NotificationTargetType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceDeliveryMethodNotificationTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesAsVoiceThroughTheBaseType() throws Exception {
        String json = """
                {"method": "VOICE", "enabled": true, "body": "Alarm on ${alarmOriginatorName}",
                 "localizedBodies": {"hi": "अलार्म ${alarmOriginatorName}"},
                 "localizedSource": "Alarm on ${alarmOriginatorName}", "ackRequired": true}""";

        DeliveryMethodNotificationTemplate parsed = MAPPER.readValue(json, DeliveryMethodNotificationTemplate.class);

        assertThat(parsed).isInstanceOf(VoiceDeliveryMethodNotificationTemplate.class);
        VoiceDeliveryMethodNotificationTemplate voice = (VoiceDeliveryMethodNotificationTemplate) parsed;
        assertThat(voice.getMethod()).isEqualTo(NotificationDeliveryMethod.VOICE);
        assertThat(voice.isAckRequired()).isTrue();
        assertThat(voice.getLocalizedBodies()).containsEntry("hi", "अलार्म ${alarmOriginatorName}");

        JsonNode written = MAPPER.readTree(MAPPER.writerFor(DeliveryMethodNotificationTemplate.class).writeValueAsString(voice));
        assertThat(written.get("method").asText()).isEqualTo("VOICE");
        assertThat(written.has("templatableValues")).isFalse();
        assertThat(written.has("localizedBodiesValid")).isFalse();
        assertThat(written.has("localizedText")).isFalse();
    }

    @Test
    void processingACopyLeavesTheOriginalAlone() {
        VoiceDeliveryMethodNotificationTemplate original = new VoiceDeliveryMethodNotificationTemplate();
        original.setBody("Alarm on ${name}");
        original.setLocalizedBodies(new HashMap<>(Map.of("hi", "अलार्म ${name}")));

        VoiceDeliveryMethodNotificationTemplate copy = (VoiceDeliveryMethodNotificationTemplate) original.copy();
        copy.getTemplatableValues().forEach(value -> value.set(value.get().replace("${name}", "AHU 1")));

        assertThat(copy.getBody()).isEqualTo("Alarm on AHU 1");
        assertThat(copy.getLocalizedBodies()).containsEntry("hi", "अलार्म AHU 1");
        assertThat(original.getBody()).isEqualTo("Alarm on ${name}");
        assertThat(original.getLocalizedBodies()).containsEntry("hi", "अलार्म ${name}");
    }

    @Test
    void aNullMapIsSafe() {
        VoiceDeliveryMethodNotificationTemplate template = new VoiceDeliveryMethodNotificationTemplate();
        template.setBody("Alarm");
        template.setLocalizedBodies(null);

        assertThat(template.getTemplatableValues()).hasSize(1);
        assertThat(template.isLocalizedBodiesValid()).isTrue();
        assertThat(template.getLocalizedText()).isEmpty();
        assertThat(((VoiceDeliveryMethodNotificationTemplate) template.copy()).getLocalizedBodies()).isEmpty();
    }

    @Test
    void onlyHindiWithinTheLimitIsValid() {
        VoiceDeliveryMethodNotificationTemplate template = new VoiceDeliveryMethodNotificationTemplate();
        Map<String, String> bodies = new HashMap<>();
        bodies.put("hi", null);
        template.setLocalizedBodies(bodies);
        assertThat(template.isLocalizedBodiesValid()).isTrue();

        bodies.put("hi", "अलार्म");
        assertThat(template.isLocalizedBodiesValid()).isTrue();

        bodies.put("ar", "إنذار"); // Arabic is not in v1
        assertThat(template.isLocalizedBodiesValid()).isFalse();

        bodies.remove("ar");
        bodies.put("hi", "x".repeat(VoiceDeliveryMethodNotificationTemplate.MAX_LENGTH + 1));
        assertThat(template.isLocalizedBodiesValid()).isFalse();
    }

    @Test
    void platformUsersCanBeCalledAndCanOptOut() {
        assertThat(NotificationTargetType.PLATFORM_USERS.getSupportedDeliveryMethods()).contains(NotificationDeliveryMethod.VOICE);
        assertThat(UserNotificationSettings.deliveryMethods).contains(NotificationDeliveryMethod.VOICE);
    }

}
