// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationType;
import org.thingsboard.server.common.data.notification.template.NotificationTemplate;
import org.thingsboard.server.common.data.notification.template.NotificationTemplateConfig;
import org.thingsboard.server.common.data.notification.template.VoiceDeliveryMethodNotificationTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What TB's own validator makes of a VOICE template, checked the way a save checks it: inside a
 * {@link NotificationTemplate}, so the {@code @Valid} cascade through the config's map is part of the test.
 * It lives in this module because {@code NoXssValidator} and {@code StringLengthValidator} do; the tests in
 * {@code common/data} cannot run them.
 */
class VoiceTemplateValidationTest {

    private static final int LIMIT = VoiceDeliveryMethodNotificationTemplate.MAX_LENGTH;

    @Test
    void aCleanHindiTemplateIsValid() {
        assertValid(clean());
    }

    @Test
    void scriptInTheHindiTextIsRejected() {
        // NoXssValidator skips maps, so getLocalizedText() is the only thing that puts the translations in front of it
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.getLocalizedBodies().put("hi", "x <script>alert(1)</script>");
        assertRejected(voice, "Voice translation is malformed");
    }

    @Test
    void scriptInTheBodyIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.setBody("x <script>alert(1)</script>");
        assertRejected(voice, "Voice message is malformed");
    }

    @Test
    void scriptInTheTranslationSourceIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.setLocalizedSource("x <script>alert(1)</script>");
        assertRejected(voice, "Voice translation source is malformed");
    }

    @Test
    void anotherLanguageIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.getLocalizedBodies().put("ar", "إنذار"); // Arabic is not in v1
        assertRejected(voice, "Voice translations must be Hindi (hi), at most 600 chars each");
    }

    @Test
    void aHindiTextIsCappedAtTheLimit() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.getLocalizedBodies().put("hi", "x".repeat(LIMIT));
        assertValid(voice);

        voice.getLocalizedBodies().put("hi", "x".repeat(LIMIT + 1));
        assertRejected(voice, "Voice translations must be Hindi (hi), at most 600 chars each");
    }

    @Test
    void aBodyOverTheLimitIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.setBody("x".repeat(LIMIT + 1));
        assertRejected(voice, "Voice message cannot be longer than 600 chars");
    }

    @Test
    void aTranslationSourceOverTheLimitIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.setLocalizedSource("x".repeat(LIMIT + 1));
        assertRejected(voice, "Voice translation source cannot be longer than 600 chars");
    }

    @Test
    void anEmptyBodyIsRejected() {
        VoiceDeliveryMethodNotificationTemplate voice = clean();
        voice.setBody("");
        assertRejected(voice, "must not be empty");
    }

    @Test
    void missingTextIsAccepted() {
        VoiceDeliveryMethodNotificationTemplate noMap = clean();
        noMap.setLocalizedBodies(null);
        assertValid(noMap);

        VoiceDeliveryMethodNotificationTemplate nullHindi = clean();
        nullHindi.getLocalizedBodies().put("hi", null);
        assertValid(nullHindi);

        VoiceDeliveryMethodNotificationTemplate noSource = clean();
        noSource.setLocalizedSource(null);
        assertValid(noSource);
    }

    @Test
    void aTemplateReadByTheProductionMapperValidatesAndKeepsItsShape() {
        NotificationTemplate parsed = JacksonUtil.fromString("""
                {"name": "Voice test", "notificationType": "ALARM", "configuration": {"deliveryMethodsTemplates": {
                  "VOICE": {"method": "VOICE", "enabled": true, "body": "Alarm on ${alarmOriginatorName}",
                            "localizedBodies": {"hi": "अलार्म ${alarmOriginatorName}"},
                            "localizedSource": "Alarm on ${alarmOriginatorName}", "ackRequired": true}}}}""", NotificationTemplate.class);

        assertThat(parsed.getConfiguration().getDeliveryMethodsTemplates().get(NotificationDeliveryMethod.VOICE))
                .isInstanceOf(VoiceDeliveryMethodNotificationTemplate.class);
        assertThatCode(() -> ConstraintValidator.validateFields(parsed)).doesNotThrowAnyException();

        JsonNode written = JacksonUtil.valueToTree(parsed).get("configuration").get("deliveryMethodsTemplates").get("VOICE");
        assertThat(written.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("method", "enabled", "body", "localizedBodies", "localizedSource", "ackRequired");
    }

    private static VoiceDeliveryMethodNotificationTemplate clean() {
        VoiceDeliveryMethodNotificationTemplate voice = new VoiceDeliveryMethodNotificationTemplate();
        voice.setEnabled(true);
        voice.setBody("Alarm on ${alarmOriginatorName}: ${alarmType}");
        voice.setLocalizedBodies(new HashMap<>(Map.of("hi", "अलार्म ${alarmOriginatorName}। ${alarmType}")));
        voice.setLocalizedSource("Alarm on ${alarmOriginatorName}: ${alarmType}");
        return voice;
    }

    private static NotificationTemplate saved(VoiceDeliveryMethodNotificationTemplate voice) {
        NotificationTemplateConfig config = new NotificationTemplateConfig();
        config.setDeliveryMethodsTemplates(new HashMap<>(Map.of(NotificationDeliveryMethod.VOICE, voice)));
        NotificationTemplate template = new NotificationTemplate();
        template.setName("Voice test");
        template.setNotificationType(NotificationType.ALARM);
        template.setConfiguration(config);
        return template;
    }

    private static void assertValid(VoiceDeliveryMethodNotificationTemplate voice) {
        assertThatCode(() -> ConstraintValidator.validateFields(saved(voice))).doesNotThrowAnyException();
    }

    private static void assertRejected(VoiceDeliveryMethodNotificationTemplate voice, String message) {
        assertThatThrownBy(() -> ConstraintValidator.validateFields(saved(voice))).hasMessageContaining(message);
    }

}
