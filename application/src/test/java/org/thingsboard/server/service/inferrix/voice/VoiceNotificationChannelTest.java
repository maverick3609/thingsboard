// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.info.AlarmNotificationInfo;
import org.thingsboard.server.common.data.notification.template.VoiceDeliveryMethodNotificationTemplate;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.CallJob;
import org.thingsboard.server.service.notification.NotificationProcessingContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoiceNotificationChannelTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final TenantId TENANT = TenantId.fromUUID(UUID.randomUUID());

    @Mock
    VoiceDialerClient dialer;
    @Mock
    AttributesService attributesService;
    @Mock
    NotificationProcessingContext ctx;
    @Mock
    ListenableFuture<Optional<AttributeKvEntry>> slowAttribute;

    private final VoiceCallTokens tokens = new VoiceCallTokens(SECRET, 3600);
    private final UUID alarmUuid = UUID.randomUUID();
    private VoiceNotificationChannel channel;

    @BeforeEach
    void setUp() {
        channel = new VoiceNotificationChannel(dialer, tokens, attributesService);
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "callbackBaseUrl", "http://127.0.0.1:8080");
        when(dialer.isConfigured()).thenReturn(true);
        NotificationRequest request = NotificationRequest.builder()
                .info(AlarmNotificationInfo.builder().alarmId(alarmUuid).build())
                .build();
        request.setId(new NotificationRequestId(UUID.randomUUID()));
        when(ctx.getTenantId()).thenReturn(TENANT);
        when(ctx.getRequest()).thenReturn(request);
        languageAttribute(null);
    }

    private void languageAttribute(String value) {
        Optional<AttributeKvEntry> entry = value == null ? Optional.empty()
                : Optional.of(new BaseAttributeKvEntry(new StringDataEntry(VoiceNotificationChannel.LANGUAGE_ATTRIBUTE, value), 0L));
        when(attributesService.find(eq(TENANT), any(), eq(AttributeScope.SERVER_SCOPE), eq(VoiceNotificationChannel.LANGUAGE_ATTRIBUTE)))
                .thenReturn(Futures.immediateFuture(entry));
    }

    private static User user(String phone, String uiLanguage) {
        User user = new User(new UserId(UUID.randomUUID()));
        user.setTenantId(TENANT);
        user.setEmail("operator@example.com");
        user.setPhone(phone);
        if (uiLanguage != null) {
            user.setAdditionalInfo(JacksonUtil.newObjectNode().put("lang", uiLanguage));
        }
        return user;
    }

    private static VoiceDeliveryMethodNotificationTemplate template(String english, Map<String, String> translations, boolean ack) {
        VoiceDeliveryMethodNotificationTemplate template = new VoiceDeliveryMethodNotificationTemplate();
        template.setEnabled(true);
        template.setBody(english);
        template.setLocalizedBodies(translations == null ? null : new HashMap<>(translations));
        template.setAckRequired(ack);
        return template;
    }

    private CallJob sentJob() {
        ArgumentCaptor<CallJob> job = ArgumentCaptor.forClass(CallJob.class);
        verify(dialer).submitCall(job.capture());
        return job.getValue();
    }

    @Test
    void callsInTheChosenLanguageWithItsStoredText() throws Exception {
        languageAttribute(" HI ");
        User user = user(" +96891234567 ", "en_US");

        channel.sendNotification(user, template("Alarm on AHU 1", Map.of("hi", "AHU 1 पर अलार्म"), true), ctx);

        CallJob job = sentJob();
        assertThat(job.to()).isEqualTo("+96891234567");
        assertThat(job.language()).isEqualTo("hi");
        assertThat(job.text()).isEqualTo("AHU 1 पर अलार्म");
        assertThat(job.ackRequired()).isTrue();
        assertThat(job.callbackBaseUrl()).isEqualTo("http://127.0.0.1:8080");
        VoiceCallClaims claims = tokens.verify(job.token());
        assertThat(claims.tenantId()).isEqualTo(TENANT.getId());
        assertThat(claims.userId()).isEqualTo(user.getUuidId());
        assertThat(claims.alarmId()).isEqualTo(alarmUuid);
        assertThat(claims.language()).isEqualTo("hi");
        assertThat(claims.languageFallback()).isFalse();
        assertThat(job.toString()).doesNotContain(job.token()).contains(job.callId()); // a log line must not carry the token
        verify(attributesService).find(TENANT, user.getId(), AttributeScope.SERVER_SCOPE, VoiceNotificationChannel.LANGUAGE_ATTRIBUTE);

        // an explicit English beats a Hindi UI
        clearInvocations(dialer);
        languageAttribute("en");

        channel.sendNotification(user("+96891234567", "hi_IN"), template("Alarm on AHU 1", Map.of("hi", "AHU 1 पर अलार्म"), true), ctx);

        assertThat(sentJob().language()).isEqualTo("en");
    }

    @Test
    void fallsBackToEnglishWhenTheLanguageHasNoText() throws Exception {
        languageAttribute("hi");

        channel.sendNotification(user("+919876543210", null), template("Alarm on AHU 1", Map.of("hi", "  "), false), ctx);

        CallJob job = sentJob();
        assertThat(job.language()).isEqualTo("en");
        assertThat(job.text()).isEqualTo("Alarm on AHU 1");
        assertThat(tokens.verify(job.token()).languageFallback()).isTrue();
        assertThat(job.ackRequired()).isFalse(); // an alarm call, but the template's switch is off
    }

    @Test
    void aTemplateWithoutTranslationsIsSpokenInEnglish() throws Exception {
        languageAttribute("hi");

        channel.sendNotification(user("+96891234567", null), template("Alarm on AHU 1", null, false), ctx);

        CallJob job = sentJob();
        assertThat(job.language()).isEqualTo("en");
        assertThat(job.text()).isEqualTo("Alarm on AHU 1");
    }

    @Test
    void theUiLanguageIsUsedWithoutTheAttribute() throws Exception {
        channel.sendNotification(user("+919876543210", "hi_IN"), template("Alarm", Map.of("hi", "अलार्म"), false), ctx);

        CallJob job = sentJob();
        assertThat(job.language()).isEqualTo("hi");
        assertThat(job.text()).isEqualTo("अलार्म");

        // An attribute that is not there within 5 s must not hold up the call either.
        clearInvocations(dialer);
        when(slowAttribute.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(attributesService.find(eq(TENANT), any(), eq(AttributeScope.SERVER_SCOPE), eq(VoiceNotificationChannel.LANGUAGE_ATTRIBUTE)))
                .thenReturn(slowAttribute);

        channel.sendNotification(user("+919876543210", "hi_IN"), template("Alarm", Map.of("hi", "अलार्म"), false), ctx);

        verify(slowAttribute).get(5, TimeUnit.SECONDS);
        assertThat(sentJob().language()).isEqualTo("hi");
    }

    @Test
    void anArabicUserIsCalledInEnglish() throws Exception {
        languageAttribute("ar");

        channel.sendNotification(user("+96891234567", "ar_AE"), template("Alarm", Map.of("hi", "अलार्म"), false), ctx);

        CallJob job = sentJob();
        assertThat(job.language()).isEqualTo("en");
        // Neither the attribute nor the UI language picked Arabic: had one, English would be a fallback.
        assertThat(tokens.verify(job.token()).languageFallback()).isFalse();
    }

    @Test
    void leftoverPlaceholdersAreNotSpoken() throws Exception {
        channel.sendNotification(user("+96891234567", null),
                template("Alarm on AHU 1. Value ${details.data}  degrees. ${alarmType:upperCase}", null, false), ctx);

        assertThat(sentJob().text()).isEqualTo("Alarm on AHU 1. Value degrees.");

        // Placeholders can expand a 600-character template past the 2000 the dialer accepts.
        clearInvocations(dialer);
        channel.sendNotification(user("+96891234567", null), template("a".repeat(2500), null, false), ctx);

        assertThat(sentJob().text()).hasSize(2000);
    }

    @Test
    void aUserWithoutAPhoneIsAnError() {
        assertThatThrownBy(() -> channel.sendNotification(user("  ", null), template("Alarm", null, false), ctx))
                .hasMessage("User does not have phone number");
        verify(dialer, never()).submitCall(any());
    }

    @Test
    void onlyAnAlarmCanBeAcknowledged() throws Exception {
        NotificationRequest general = NotificationRequest.builder().build();
        general.setId(new NotificationRequestId(UUID.randomUUID()));
        when(ctx.getRequest()).thenReturn(general);

        channel.sendNotification(user("+96891234567", null), template("Maintenance tonight", null, true), ctx);

        CallJob job = sentJob();
        assertThat(job.ackRequired()).isFalse();
        assertThat(tokens.verify(job.token()).alarmId()).isNull();
    }

    @Test
    void checkLooksAtConfigurationOnly() throws Exception {
        channel.check(TENANT);
        verifyNoInteractions(attributesService);
        verify(dialer, never()).submitCall(any());
        verify(dialer, never()).translate(any(), any());

        when(dialer.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> channel.check(TENANT)).hasMessage("Voice calls are not configured");

        // A secret too short to sign with leaves voice unconfigured too: else VOICE is offered and every call fails.
        when(dialer.isConfigured()).thenReturn(true);
        VoiceNotificationChannel unsigned = new VoiceNotificationChannel(dialer, new VoiceCallTokens("too short", 3600), attributesService);
        ReflectionTestUtils.setField(unsigned, "enabled", true);
        assertThatThrownBy(() -> unsigned.check(TENANT)).hasMessage("Voice calls are not configured");

        ReflectionTestUtils.setField(channel, "enabled", false);
        assertThatThrownBy(() -> channel.check(TENANT)).hasMessage("Voice calls are disabled");
    }

}
