// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmCommentInfo;
import org.thingsboard.server.common.data.alarm.AlarmCommentType;
import org.thingsboard.server.common.data.alarm.AlarmSearchStatus;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestInfo;
import org.thingsboard.server.common.data.notification.NotificationType;
import org.thingsboard.server.common.data.notification.rule.EscalatedNotificationRuleRecipientsConfig;
import org.thingsboard.server.common.data.notification.rule.NotificationRule;
import org.thingsboard.server.common.data.notification.rule.trigger.config.AlarmNotificationRuleTriggerConfig;
import org.thingsboard.server.common.data.notification.rule.trigger.config.AlarmNotificationRuleTriggerConfig.AlarmAction;
import org.thingsboard.server.common.data.notification.rule.trigger.config.NotificationRuleTriggerType;
import org.thingsboard.server.common.data.notification.template.NotificationTemplate;
import org.thingsboard.server.common.data.notification.template.NotificationTemplateConfig;
import org.thingsboard.server.common.data.notification.template.VoiceDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.controller.InferrixVoiceController;
import org.thingsboard.server.dao.alarm.AlarmCommentService;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.CallJob;
import org.thingsboard.server.service.notification.AbstractNotificationApiTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path with only the dialer mocked: a real alarm, a real notification rule with an
 * escalation table, the noauth callbacks as the dialer would call them, and what lands in the database.
 */
@DaoSqlTest
@TestPropertySource(properties = {
        "inferrix.voice.enabled=true",
        "inferrix.voice.dialer_token=test-dialer-token",
        "inferrix.voice.token_secret=0123456789abcdef0123456789abcdef"
})
public class VoiceAlarmCallTest extends AbstractNotificationApiTest {

    private static final String ALARM_TYPE = "High temperature";
    private static final String CALLBACKS = "/api/noauth/inferrix/voice/v1/";

    @MockitoBean
    private VoiceDialerClient dialer;
    @MockitoSpyBean
    private VoiceCallbackService callbacks;
    @Autowired
    private InferrixVoiceController controller;
    @Autowired
    private UserService userService;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private AlarmCommentService alarmCommentService;

    private final List<CallJob> jobs = new CopyOnWriteArrayList<>();

    @Before
    public void beforeEach() throws Exception {
        when(dialer.isConfigured()).thenReturn(true);
        doAnswer(invocation -> jobs.add(invocation.getArgument(0))).when(dialer).submitCall(any());
        loginTenantAdmin();
        setPhone(tenantAdminUserId, "+96891234567");
        setPhone(customerUserId, "+919876543210");
    }

    @Test
    public void pressingOneAcknowledgesAsTheCalledUserAndStopsEscalation() throws Exception {
        createVoiceRule(Map.of(0, tenantAdminUserId, 1000, customerUserId));
        Device device = createDevice("AHU 1", "ahu-1-token");
        Alarm alarm = createAlarm(device);

        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> jobs.size() == 1);
        CallJob job = jobs.get(0);
        assertThat(job.to()).isEqualTo("+96891234567");
        assertThat(job.language()).isEqualTo("en");
        assertThat(job.text()).isEqualTo("Alert from Cortex. Alarm on AHU 1. Value 32.5.");
        assertThat(job.ackRequired()).isTrue();
        assertThat(job.callbackBaseUrl()).isEqualTo("http://127.0.0.1:8080");
        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> scheduledRequests().size() == 1);

        resetTokens(); // the dialer has no session: the token in the body is the only credential
        assertThat(callback("precheck", Map.of("token", job.token())).get("proceed").asBoolean()).isTrue();
        assertThat(callback("ack", Map.of("token", job.token())).get("acknowledged").asBoolean()).isTrue();

        assertThat(alarmService.findAlarmById(tenantId, alarm.getId()).isAcknowledged()).isTrue();
        assertThat(comments(alarm)).anySatisfy(comment -> {
            assertThat(comment.getType()).isEqualTo(AlarmCommentType.SYSTEM);
            assertThat(comment.getComment().get("subtype").asText()).isEqualTo("ACKED_BY_USER");
            assertThat(comment.getUserId()).isEqualTo(tenantAdminUserId);
        });
        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> scheduledRequests().isEmpty());

        long endedAt = System.currentTimeMillis() - 1000;
        doPost(CALLBACKS + "result", Map.of("token", job.token(), "callId", job.callId(), "outcome", "ACKNOWLEDGED",
                "sipStatus", 200, "ringSec", 4, "talkSec", 21, "speechFallback", false, "endedAt", endedAt))
                .andExpect(status().isNoContent());
        await().atMost(TIMEOUT, TimeUnit.SECONDS).untilAsserted(() -> {
            TsKvEntry call = tsService.findLatest(tenantId, device.getId(), "voiceCall").get().orElseThrow();
            assertThat(call.getTs()).isEqualTo(endedAt);
            assertThat(JacksonUtil.toJsonNode(call.getJsonValue().orElseThrow()).get("outcome").asText()).isEqualTo("ACKNOWLEDGED");
        });
        assertThat(comments(alarm)).anySatisfy(comment ->
                assertThat(comment.getComment().get("text").asText()).isEqualTo("Voice call to "
                        + userService.findUserById(tenantId, tenantAdminUserId).getTitle() + " (…4567), English: acknowledged"));

        // an outcome the contract does not have is the dialer's mistake: a 400, which it never retries
        doPost(CALLBACKS + "result", Map.of("token", job.token(), "callId", job.callId(), "outcome", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void anAlarmClearedWithoutAckStillReachesTheDialerAndPrecheckSkipsIt() throws Exception {
        createVoiceRule(Map.of(0, tenantAdminUserId, 2, customerUserId));
        Alarm alarm = createAlarm(createDevice("AHU 2", "ahu-2-token"));
        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> jobs.size() == 1);

        doPost("/api/alarm/" + alarm.getId() + "/clear").andExpect(status().isOk());

        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> jobs.size() == 2);
        CallJob second = jobs.get(1);
        assertThat(second.to()).isEqualTo("+919876543210");
        resetTokens();
        JsonNode precheck = callback("precheck", Map.of("token", second.token()));
        assertThat(precheck.get("proceed").asBoolean()).isFalse();
        assertThat(precheck.get("reason").asText()).isEqualTo("cleared");
    }

    @Test
    public void aForgedTokenIsRefused() throws Exception {
        resetTokens();
        String forged = "eyJ2IjoxfQ.AAAA";

        doPost(CALLBACKS + "precheck", Map.of("token", forged)).andExpect(status().isUnauthorized());
        doPost(CALLBACKS + "ack", Map.of("token", forged)).andExpect(status().isUnauthorized());
        doPost(CALLBACKS + "result", Map.of("token", forged, "outcome", "ANSWERED")).andExpect(status().isUnauthorized());
        for (String endpoint : List.of("precheck", "ack", "result")) { // no token at all
            doPost(CALLBACKS + endpoint, Map.of()).andExpect(status().isUnauthorized());
        }

        // The service's own ThingsboardException keeps its status: an alarm deleted mid-acknowledgement is a 404,
        // which the dialer reports as refused.
        doThrow(new ThingsboardException(ThingsboardErrorCode.ITEM_NOT_FOUND)).when(callbacks).ack(any());
        doPost(CALLBACKS + "ack", Map.of("token", forged)).andExpect(status().isNotFound());

        // Anything else the service throws is a fixed 5xx the dialer retries: BaseController would make a 400 of
        // an IllegalArgumentException, and would put the message of the rest into the body. The detail is logged.
        doThrow(new IllegalArgumentException("secret detail")).when(callbacks).precheck(any());
        doThrow(new IllegalStateException("secret detail")).when(callbacks).ack(any());
        doThrow(new ExecutionException(new IllegalArgumentException("secret detail"))).when(callbacks).result(any());
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        Logger controllerLog = (Logger) LoggerFactory.getLogger(InferrixVoiceController.class);
        controllerLog.addAppender(logged);
        try {
            for (String endpoint : List.of("precheck", "ack", "result")) {
                doPost(CALLBACKS + endpoint, Map.of("token", forged)).andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.message").value("Voice callback failed"));
            }
        } finally {
            controllerLog.detachAppender(logged);
        }
        assertThat(logged.list).hasSize(3).allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("secret detail"));

        // An interrupt is not swallowed with the failure: MockMvc runs on this thread, which must still be marked.
        doThrow(new InterruptedException()).when(callbacks).result(any());
        int answered = doPost(CALLBACKS + "result", Map.of("token", forged)).andReturn().getResponse().getStatus();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(answered).isEqualTo(500);
    }

    @Test
    public void translateIsForAdministratorsAndPassesThroughACleanShape() throws Exception {
        ObjectNode answer = JacksonUtil.newObjectNode();
        answer.putObject("hi").put("text", "अलार्म").put("placeholdersOk", false).put("debug", "x");
        answer.putObject("ar").put("text", "إنذار").put("placeholdersOk", true); // not asked for: dropped
        answer.putObject("fr").put("text", "Alarme");
        when(dialer.translate(any(), any())).thenReturn(answer);

        JsonNode response = doPost("/api/inferrix/voice/translate", Map.of("text", "Alarm on ${alarmOriginatorName}"), JsonNode.class);

        assertThat(response).isEqualTo(JacksonUtil.toJsonNode("""
                {"translations": {"hi": {"text": "अलार्म", "placeholdersOk": false}}}"""));
        doPost("/api/inferrix/voice/translate", Map.of("text", "x".repeat(601))).andExpect(status().isBadRequest());
        doPost("/api/inferrix/voice/translate", Map.of("text", "x".repeat(600))).andExpect(status().isOk()); // the limit itself
        doPost("/api/inferrix/voice/translate", Map.of()).andExpect(status().isBadRequest());
        doPost("/api/inferrix/voice/translate", Map.of("text", " ")).andExpect(status().isBadRequest());

        ObjectNode unflagged = JacksonUtil.newObjectNode();
        unflagged.putObject("hi").put("text", "अलार्म");
        when(dialer.translate(any(), any())).thenReturn(unflagged);
        assertThat(doPost("/api/inferrix/voice/translate", Map.of("text", "Alarm"), JsonNode.class)
                .at("/translations/hi/placeholdersOk").asBoolean(true)).isFalse(); // never assumed to be fine

        when(dialer.isConfigured()).thenReturn(false);
        doPost("/api/inferrix/voice/translate", Map.of("text", "Alarm")).andExpect(status().isBadRequest());
        when(dialer.isConfigured()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "enabled", false);
        try {
            doPost("/api/inferrix/voice/translate", Map.of("text", "Alarm")).andExpect(status().isBadRequest());
        } finally {
            ReflectionTestUtils.setField(controller, "enabled", true);
        }

        loginCustomerUser();
        doPost("/api/inferrix/voice/translate", Map.of("text", "Alarm")).andExpect(status().isForbidden());
    }

    private void setPhone(UserId userId, String phone) {
        User user = userService.findUserById(tenantId, userId);
        user.setPhone(phone);
        userService.saveUser(tenantId, user);
    }

    private void createVoiceRule(Map<Integer, UserId> levels) {
        VoiceDeliveryMethodNotificationTemplate voice = new VoiceDeliveryMethodNotificationTemplate();
        voice.setEnabled(true);
        voice.setBody("Alert from Cortex. Alarm on ${alarmOriginatorName}. Value ${details.data}.");
        voice.setAckRequired(true);
        NotificationTemplateConfig config = new NotificationTemplateConfig();
        config.setDeliveryMethodsTemplates(new HashMap<>(Map.of(NotificationDeliveryMethod.VOICE, voice)));
        NotificationTemplate template = new NotificationTemplate();
        template.setTenantId(tenantId);
        template.setName("Voice alarm call");
        template.setNotificationType(NotificationType.ALARM);
        template.setConfiguration(config);
        template = saveNotificationTemplate(template);

        AlarmNotificationRuleTriggerConfig trigger = new AlarmNotificationRuleTriggerConfig();
        trigger.setAlarmTypes(Set.of(ALARM_TYPE));
        trigger.setNotifyOn(Set.of(AlarmAction.CREATED));
        AlarmNotificationRuleTriggerConfig.ClearRule clearRule = new AlarmNotificationRuleTriggerConfig.ClearRule();
        clearRule.setAlarmStatuses(Set.of(AlarmSearchStatus.ACK)); // Acknowledged only: ticked statuses combine with AND
        trigger.setClearRule(clearRule);

        EscalatedNotificationRuleRecipientsConfig recipients = new EscalatedNotificationRuleRecipientsConfig();
        Map<Integer, List<UUID>> escalationTable = new HashMap<>();
        levels.forEach((delaySec, userId) -> escalationTable.put(delaySec, List.of(createNotificationTarget(userId).getUuidId())));
        recipients.setEscalationTable(escalationTable);

        NotificationRule rule = new NotificationRule();
        rule.setName("Voice escalation");
        rule.setEnabled(true);
        rule.setTemplateId(template.getId());
        rule.setTriggerType(NotificationRuleTriggerType.ALARM);
        rule.setTriggerConfig(trigger);
        rule.setRecipientsConfig(recipients);
        saveNotificationRule(rule);
    }

    private Alarm createAlarm(Device device) {
        Alarm alarm = Alarm.builder()
                .tenantId(tenantId)
                .originator(device.getId())
                .type(ALARM_TYPE)
                .severity(AlarmSeverity.CRITICAL)
                .details(JacksonUtil.newObjectNode().put("data", "32.5"))
                .build();
        return doPost("/api/alarm", alarm, Alarm.class);
    }

    private List<NotificationRequestInfo> scheduledRequests() {
        return notificationRequestService.findNotificationRequestsInfosByTenantIdAndOriginatorType(tenantId, EntityType.ALARM, new PageLink(100))
                .getData().stream().filter(NotificationRequest::isScheduled).toList();
    }

    private List<AlarmCommentInfo> comments(Alarm alarm) {
        return alarmCommentService.findAlarmComments(tenantId, alarm.getId(), new PageLink(100)).getData();
    }

    private JsonNode callback(String name, Object body) {
        return doPost(CALLBACKS + name, body, JsonNode.class);
    }

}
