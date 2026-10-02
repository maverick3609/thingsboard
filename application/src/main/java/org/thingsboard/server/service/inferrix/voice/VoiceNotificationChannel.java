// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.info.AlarmNotificationInfo;
import org.thingsboard.server.common.data.notification.template.VoiceDeliveryMethodNotificationTemplate;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.CallJob;
import org.thingsboard.server.service.notification.NotificationProcessingContext;
import org.thingsboard.server.service.notification.channels.NotificationChannel;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hands a VOICE notification to inferrix-dialer. "Sent" in the Notification Center means the dialer
 * queued the call; how the call went is recorded on the alarm when the dialer reports back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceNotificationChannel implements NotificationChannel<User, VoiceDeliveryMethodNotificationTemplate> {

    /** Server attribute on the user, set by an administrator on the user's Attributes tab: en or hi. */
    static final String LANGUAGE_ATTRIBUTE = "voiceLanguage";
    static final Set<String> LANGUAGES = Set.of("en", "hi");
    /** The dialer refuses a longer text, and placeholders can expand a 600-character template past it. */
    static final int MAX_SPOKEN_LENGTH = 2000;
    private static final Pattern LEFTOVER_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");

    private final VoiceDialerClient dialer;
    private final VoiceCallTokens tokens;
    private final AttributesService attributesService;

    @Value("${inferrix.voice.enabled:false}")
    private boolean enabled;
    @Value("${inferrix.voice.callback_base_url:http://127.0.0.1:8080}")
    private String callbackBaseUrl;

    @Override
    public void sendNotification(User recipient, VoiceDeliveryMethodNotificationTemplate processedTemplate,
                                 NotificationProcessingContext ctx) throws Exception {
        String phone = StringUtils.trimToNull(recipient.getPhone());
        if (phone == null) {
            throw new RuntimeException("User does not have phone number");
        }
        String language = languageOf(ctx.getTenantId(), recipient);
        String text = processedTemplate.getBody();
        boolean languageFallback = false;
        if (!"en".equals(language)) {
            Map<String, String> translations = processedTemplate.getLocalizedBodies();
            String translated = translations == null ? null : translations.get(language);
            if (StringUtils.isBlank(translated)) {
                language = "en"; // the Hindi voice cannot read English
                languageFallback = true;
            } else {
                text = translated;
            }
        }
        // TB leaves a ${key} it has no value for in place; the listener must not hear it read out.
        text = LEFTOVER_PLACEHOLDER.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        text = StringUtils.truncate(text, MAX_SPOKEN_LENGTH);

        AlarmId alarmId = ctx.getRequest().getInfo() instanceof AlarmNotificationInfo info && info.getAlarmId() != null
                ? new AlarmId(info.getAlarmId()) : null;
        String token = tokens.mint(VoiceCallClaims.of(ctx.getTenantId(), ctx.getRequest().getId(), recipient.getId(),
                alarmId, language, languageFallback));
        dialer.submitCall(new CallJob(UUID.randomUUID().toString(), phone, language, text,
                processedTemplate.isAckRequired() && alarmId != null, token, callbackBaseUrl));
    }

    /**
     * Configuration only; never a call to the dialer. When this throws for a rule-triggered request,
     * TB skips the delivery method without recording anything, and the same check hides VOICE from
     * every user's notification settings. A dialer that is down must instead fail each call, which TB
     * records per recipient under Sent.
     */
    @Override
    public void check(TenantId tenantId) throws Exception {
        if (!enabled) {
            throw new RuntimeException("Voice calls are disabled");
        }
        if (!dialer.isConfigured() || !tokens.isConfigured()) {
            throw new RuntimeException("Voice calls are not configured");
        }
    }

    @Override
    public NotificationDeliveryMethod getDeliveryMethod() {
        return NotificationDeliveryMethod.VOICE;
    }

    private String languageOf(TenantId tenantId, User user) {
        try {
            String chosen = attributesService.find(tenantId, user.getId(), AttributeScope.SERVER_SCOPE, LANGUAGE_ATTRIBUTE)
                    .get(5, TimeUnit.SECONDS)
                    .flatMap(AttributeKvEntry::getStrValue)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .orElse(null);
            if (chosen != null && LANGUAGES.contains(chosen)) {
                return chosen;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[{}] Could not read {} of user {}: {}", tenantId, LANGUAGE_ATTRIBUTE, user.getId(), e.toString());
        }
        JsonNode lang = user.getAdditionalInfo() == null ? null : user.getAdditionalInfo().get("lang");
        String uiLanguage = lang != null && lang.isTextual() ? lang.asText() : "";
        return uiLanguage.startsWith("hi") ? "hi" : "en";
    }

}
