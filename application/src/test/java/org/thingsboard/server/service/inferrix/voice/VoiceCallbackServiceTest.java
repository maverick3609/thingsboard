// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.TimeseriesSaveRequest;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.UserAuthDetails;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmComment;
import org.thingsboard.server.common.data.alarm.AlarmCommentType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.service.entitiy.alarm.TbAlarmCommentService;
import org.thingsboard.server.service.entitiy.alarm.TbAlarmService;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.Ack;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.CallResult;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.Precheck;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;
import org.thingsboard.server.service.security.permission.MergedUserPermissions;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.service.security.permission.UserPermissionsService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;
import org.thingsboard.server.service.user.cache.UserAuthDetailsCache;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoiceCallbackServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final TenantId TENANT = TenantId.fromUUID(UUID.randomUUID());

    @Mock
    AlarmService alarmService;
    @Mock
    UserAuthDetailsCache userAuthDetailsCache;
    @Mock
    UserPermissionsService userPermissionsService;
    @Mock
    AccessControlService accessControlService;
    @Mock
    TbAlarmService tbAlarmService;
    @Mock
    TbAlarmCommentService tbAlarmCommentService;
    @Mock
    TelemetrySubscriptionService telemetryService;

    private final VoiceCallTokens tokens = new VoiceCallTokens(SECRET, 3600);
    private VoiceCallbackService service;
    private Alarm alarm;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        service = new VoiceCallbackService(tokens, alarmService, userAuthDetailsCache, userPermissionsService,
                accessControlService, tbAlarmService, tbAlarmCommentService, telemetryService);
        alarm = new Alarm(new AlarmId(UUID.randomUUID()));
        alarm.setTenantId(TENANT);
        alarm.setType("High temperature");
        alarm.setOriginator(new DeviceId(UUID.randomUUID()));
        user = new User(new UserId(UUID.randomUUID()));
        user.setTenantId(TENANT);
        user.setAuthority(Authority.CUSTOMER_USER);
        user.setEmail("asha@example.com");
        user.setFirstName("Asha");
        user.setLastName("Rao");
        user.setPhone("+91 98765 43210");
        when(alarmService.findAlarmById(eq(TENANT), eq(alarm.getId()))).thenReturn(alarm);
        when(userAuthDetailsCache.getUserAuthDetails(eq(TENANT), eq(user.getId()))).thenReturn(new UserAuthDetails(user, true));
        when(accessControlService.hasPermission(any(), eq(Resource.ALARM), eq(Operation.WRITE), eq(alarm.getId()), eq(alarm)))
                .thenReturn(true);
        doAnswer(invocation -> {
            invocation.<TimeseriesSaveRequest>getArgument(0).getCallback().onSuccess(null);
            return null;
        }).when(telemetryService).saveTimeseries(any());
    }

    private String token() {
        return token(tokens, alarm.getId(), "hi", false);
    }

    private String token(VoiceCallTokens signer, AlarmId alarmId, String language, boolean languageFallback) {
        return signer.mint(VoiceCallClaims.of(TENANT, new NotificationRequestId(UUID.randomUUID()), user.getId(),
                alarmId, language, languageFallback));
    }

    private static CallResult result(String token, String outcome, long endedAt) {
        return new CallResult(token, "c1", outcome, 200, 4, 21, false, endedAt);
    }

    private List<String> comments() throws Exception {
        ArgumentCaptor<AlarmComment> comment = ArgumentCaptor.forClass(AlarmComment.class);
        verify(tbAlarmCommentService, atLeast(0)).saveAlarmComment(any(), comment.capture(), isNull());
        comment.getAllValues().forEach(c -> assertThat(c.getType()).isEqualTo(AlarmCommentType.SYSTEM));
        return comment.getAllValues().stream().map(c -> c.getComment().get("text").asText()).toList();
    }

    private TsKvEntry savedTelemetry() {
        ArgumentCaptor<TimeseriesSaveRequest> request = ArgumentCaptor.forClass(TimeseriesSaveRequest.class);
        verify(telemetryService).saveTimeseries(request.capture());
        assertThat(request.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(request.getValue().getEntityId()).isEqualTo(alarm.getOriginator());
        assertThat(request.getValue().getEntries()).hasSize(1);
        return request.getValue().getEntries().get(0);
    }

    private static void assertError(ThrowingCall call, ThingsboardErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ThingsboardException.class)
                .extracting(e -> ((ThingsboardException) e).getErrorCode())
                .isEqualTo(code);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ---- precheck

    @Test
    void precheckProceedsForAnOpenAlarm() throws Exception {
        assertThat(service.precheck(token())).isEqualTo(new Precheck(true, null));
    }

    @Test
    void precheckSkipsAnAcknowledgedAlarm() throws Exception {
        alarm.setAcknowledged(true);
        assertThat(service.precheck(token())).isEqualTo(new Precheck(false, "acknowledged"));
    }

    @Test
    void precheckSkipsAClearedAlarm() throws Exception {
        alarm.setCleared(true);
        assertThat(service.precheck(token())).isEqualTo(new Precheck(false, "cleared"));
    }

    @Test
    void precheckSaysExpired() throws Exception {
        String expired = token(new VoiceCallTokens(SECRET, -60), alarm.getId(), "hi", false);
        assertThat(service.precheck(expired)).isEqualTo(new Precheck(false, "expired"));
    }

    // ---- tenant isolation

    @Test
    void aForeignTenantsAlarmIsNotFound() throws Exception {
        Alarm foreign = new Alarm(new AlarmId(UUID.randomUUID()));
        foreign.setTenantId(TenantId.fromUUID(UUID.randomUUID()));
        when(alarmService.findAlarmById(eq(TENANT), eq(foreign.getId()))).thenReturn(foreign);
        String token = token(tokens, foreign.getId(), "hi", false);

        assertThat(service.precheck(token)).isEqualTo(new Precheck(false, "not-found"));
        assertThat(service.ack(token)).isEqualTo(new Ack(false, "not-found"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void aForeignTenantsUserCannotAcknowledge() throws Exception {
        user.setTenantId(TenantId.fromUUID(UUID.randomUUID()));

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "user-disabled"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    // ---- ack

    @Test
    void ackRunsAsTheCalledUserWithTheirRolePermissions() throws Exception {
        user.setCustomerId(new CustomerId(UUID.randomUUID()));
        MergedUserPermissions readOnly = new MergedUserPermissions(Map.of(Resource.ALARM, Set.of(Operation.READ)));
        when(userPermissionsService.getMergedPermissions(any())).thenReturn(readOnly);
        when(accessControlService.hasPermission(any(), eq(Resource.ALARM), eq(Operation.WRITE), eq(alarm.getId()), eq(alarm)))
                .thenReturn(false);

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "permission"));

        ArgumentCaptor<SecurityUser> actor = ArgumentCaptor.forClass(SecurityUser.class);
        verify(accessControlService).hasPermission(actor.capture(), eq(Resource.ALARM), eq(Operation.WRITE), eq(alarm.getId()), eq(alarm));
        assertThat(actor.getValue().getId()).isEqualTo(user.getId());
        assertThat(actor.getValue().getAuthority()).isEqualTo(Authority.CUSTOMER_USER);
        assertThat(actor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(actor.getValue().getCustomerId()).isEqualTo(user.getCustomerId());
        assertThat(actor.getValue().getUserPermissions()).isSameAs(readOnly);
        verify(tbAlarmService, never()).ack(any(), any());
        assertThat(comments()).containsExactly("Voice acknowledgement by Asha Rao refused (permission)");

        // without the right to acknowledge, an acknowledged alarm must not read as "already-acknowledged"
        alarm.setAcknowledged(true);
        assertThat(service.ack(token())).isEqualTo(new Ack(false, "permission"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void ackAcknowledgesWhenPermitted() throws Exception {
        assertThat(service.ack(token())).isEqualTo(new Ack(true, null));

        ArgumentCaptor<User> actor = ArgumentCaptor.forClass(User.class);
        verify(tbAlarmService).ack(eq(alarm), actor.capture());
        assertThat(actor.getValue().getId()).isEqualTo(user.getId());
        assertThat(comments()).isEmpty();
    }

    @Test
    void ackRefusesAnExpiredToken() throws Exception {
        String expired = token(new VoiceCallTokens(SECRET, -60), alarm.getId(), "hi", false);

        assertThat(service.ack(expired)).isEqualTo(new Ack(false, "expired"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void aClearedAlarmCanStillBeAcknowledged() throws Exception {
        alarm.setCleared(true);

        assertThat(service.ack(token())).isEqualTo(new Ack(true, null));
        verify(tbAlarmService).ack(eq(alarm), any());
    }

    @Test
    void alreadyAcknowledgedIsAcknowledged() throws Exception {
        alarm.setAcknowledged(true);

        assertThat(service.ack(token())).isEqualTo(new Ack(true, "already-acknowledged"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void aDisabledUserCannotAcknowledge() throws Exception {
        when(userAuthDetailsCache.getUserAuthDetails(eq(TENANT), eq(user.getId()))).thenReturn(new UserAuthDetails(user, false));

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "user-disabled"));
        verify(tbAlarmService, never()).ack(any(), any());
        assertThat(comments()).containsExactly("Voice acknowledgement by an unknown or disabled user refused (user-disabled)");

        // only tenant admins and customer users may acknowledge, as AlarmController.ackAlarm's @PreAuthorize says
        user.setAuthority(Authority.SYS_ADMIN);
        when(userAuthDetailsCache.getUserAuthDetails(eq(TENANT), eq(user.getId()))).thenReturn(new UserAuthDetails(user, true));

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "user-disabled"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void permissionsThatCannotBeLoadedFailClosed() throws Exception {
        when(userPermissionsService.getMergedPermissions(any())).thenThrow(new RuntimeException("cache down"));

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "permission"));
        verify(accessControlService, never()).hasPermission(any(), any(), any(), any(), any());
        verify(tbAlarmService, never()).ack(any(), any());

        // the access check can also refuse by throwing, for instance when it has no checker for the user's authority
        doReturn(null).when(userPermissionsService).getMergedPermissions(any());
        when(accessControlService.hasPermission(any(), eq(Resource.ALARM), eq(Operation.WRITE), eq(alarm.getId()), eq(alarm)))
                .thenThrow(new ThingsboardException(ThingsboardErrorCode.PERMISSION_DENIED));

        assertThat(service.ack(token())).isEqualTo(new Ack(false, "permission"));
        verify(tbAlarmService, never()).ack(any(), any());
    }

    @Test
    void aRaceWithTheUiStillAnswersAcknowledged() throws Exception {
        Alarm acknowledged = new Alarm(alarm.getId());
        acknowledged.setTenantId(TENANT);
        acknowledged.setAcknowledged(true);
        when(tbAlarmService.ack(eq(alarm), any()))
                .thenThrow(new ThingsboardException("Alarm was already acknowledged!", ThingsboardErrorCode.BAD_REQUEST_PARAMS));
        when(alarmService.findAlarmById(eq(TENANT), eq(alarm.getId()))).thenReturn(alarm, acknowledged);

        assertThat(service.ack(token())).isEqualTo(new Ack(true, "already-acknowledged"));
    }

    @Test
    void anAckThatFailsForAnotherReasonIsAnError() throws Exception {
        when(tbAlarmService.ack(eq(alarm), any())).thenThrow(new ThingsboardException("db down", ThingsboardErrorCode.GENERAL));

        assertError(() -> service.ack(token()), ThingsboardErrorCode.GENERAL);
    }

    @Test
    void aNonAlarmCallHasNothingToAcknowledge() throws Exception {
        String token = token(tokens, null, "hi", false);

        assertThat(service.ack(token)).isEqualTo(new Ack(false, "not-an-alarm"));
        assertThat(service.precheck(token)).isEqualTo(new Precheck(true, null));

        // rid and lang are optional claims, so a token without them is still valid, and its result is only logged
        String bare = tokens.mint(VoiceCallClaims.of(TENANT, null, user.getId(), null, null, false));
        assertThat(service.ack(bare)).isEqualTo(new Ack(false, "not-an-alarm"));
        assertThat(service.precheck(bare)).isEqualTo(new Precheck(true, null));
        service.result(result(bare, "NO_ANSWER", System.currentTimeMillis()));
        verifyNoInteractions(telemetryService);
        assertThat(comments()).isEmpty();
    }

    // ---- result

    @Test
    void resultWritesACommentAndTelemetry() throws Exception {
        long endedAt = System.currentTimeMillis() - 5000;

        service.result(result(token(), "ACKNOWLEDGED", endedAt));

        assertThat(comments()).containsExactly("Voice call to Asha Rao (…3210), Hindi: acknowledged");
        TsKvEntry entry = savedTelemetry();
        assertThat(entry.getKey()).isEqualTo("voiceCall");
        assertThat(entry.getTs()).isEqualTo(endedAt);
        JsonNode call = JacksonUtil.toJsonNode(entry.getJsonValue().orElseThrow());
        assertThat(call.get("alarmId").asText()).isEqualTo(alarm.getUuidId().toString());
        assertThat(call.get("alarmType").asText()).isEqualTo("High temperature");
        assertThat(call.get("userName").asText()).isEqualTo("Asha Rao");
        assertThat(call.get("phoneLast4").asText()).isEqualTo("3210");
        assertThat(call.get("language").asText()).isEqualTo("hi");
        assertThat(call.get("outcome").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(call.get("talkSec").asInt()).isEqualTo(21);
        assertThat(call.get("languageFallback").asBoolean()).isFalse();
        assertThat(call.toString()).doesNotContain("+91 98765");
        assertThat(call.toString()).doesNotContain("98765"); // nor the number without its spaces
    }

    @Test
    void aSkippedCallIsTelemetryOnly() throws Exception {
        service.result(result(token(), "SKIPPED", System.currentTimeMillis()));

        assertThat(comments()).isEmpty();
        assertThat(savedTelemetry().getKey()).isEqualTo("voiceCall");
    }

    @Test
    void resultRefusesAnUnknownOutcome() {
        assertError(() -> service.result(result(token(), "EXPLODED", 0)), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        assertError(() -> service.result(result(token(), null, 0)), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        verifyNoInteractions(telemetryService);
    }

    @Test
    void aLateResultIsAcceptedWithinTheGrace() throws Exception {
        String late = token(new VoiceCallTokens(SECRET, -60), alarm.getId(), "hi", false);

        service.result(result(late, "NO_ANSWER", System.currentTimeMillis()));

        assertThat(comments()).containsExactly("Voice call to Asha Rao (…3210), Hindi: no answer");
    }

    @Test
    void aResultPastTheGraceIsRefused() {
        String stale = token(new VoiceCallTokens(SECRET, -1200), alarm.getId(), "hi", false);

        assertError(() -> service.result(result(stale, "NO_ANSWER", 0)), ThingsboardErrorCode.AUTHENTICATION);
    }

    @Test
    void aWildEndedAtIsClamped() throws Exception {
        long before = System.currentTimeMillis();

        service.result(result(token(), "BUSY", 42L));

        assertThat(savedTelemetry().getTs()).isGreaterThanOrEqualTo(before);

        clearInvocations(telemetryService);
        service.result(result(token(), "BUSY", Long.MAX_VALUE));

        assertThat(savedTelemetry().getTs()).isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test
    void aMissingTranslationIsNamedInTheComment() throws Exception {
        service.result(result(token(tokens, alarm.getId(), "en", true), "ANSWERED", System.currentTimeMillis()));

        assertThat(comments()).containsExactly("Voice call to Asha Rao (…3210), English (translation missing): answered, not acknowledged");

        // a token without a lang claim is recorded as English
        service.result(result(tokens.mint(VoiceCallClaims.of(TENANT, null, user.getId(), alarm.getId(), null, false)),
                "ANSWERED", System.currentTimeMillis()));
        assertThat(comments()).last().isEqualTo("Voice call to Asha Rao (…3210), English: answered, not acknowledged");
    }

    @Test
    void aResultForADeletedUserStillRecordsTheCall() throws Exception {
        when(userAuthDetailsCache.getUserAuthDetails(eq(TENANT), eq(user.getId()))).thenReturn(null);

        service.result(result(token(), "NO_ANSWER", System.currentTimeMillis()));

        assertThat(comments()).containsExactly("Voice call to a deleted user, Hindi: no answer");
    }

    @Test
    void aTelemetryWriteThatFailsIsAnErrorSoTheDialerRetries() throws Exception {
        doAnswer(invocation -> {
            invocation.<TimeseriesSaveRequest>getArgument(0).getCallback().onFailure(new RuntimeException("db down"));
            return null;
        }).when(telemetryService).saveTimeseries(any());

        assertThatThrownBy(() -> service.result(result(token(), "NO_ANSWER", System.currentTimeMillis())))
                .isInstanceOf(ExecutionException.class);
        assertThat(comments()).isEmpty();
    }

}
