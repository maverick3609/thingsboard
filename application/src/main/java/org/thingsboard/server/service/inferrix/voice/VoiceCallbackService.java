// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.SettableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.TimeseriesSaveRequest;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.UserAuthDetails;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmComment;
import org.thingsboard.server.common.data.alarm.AlarmCommentType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.JsonDataEntry;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.alarm.TbAlarmCommentService;
import org.thingsboard.server.service.entitiy.alarm.TbAlarmService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.UserPrincipal;
import org.thingsboard.server.service.security.permission.AccessControlService;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.service.security.permission.UserPermissionsService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;
import org.thingsboard.server.service.user.cache.UserAuthDetailsCache;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What inferrix-dialer's callbacks do. There is no session: the signed token says who was called
 * about which alarm. Every rule a person pressing "acknowledge" in the web UI would meet applies
 * here as that person: their tenant, their account being enabled (and so activated), their authority
 * (tenant administrator or customer user), their role permissions. Login checks do not apply:
 * two-factor authentication and password expiry gate a session, and whoever answers the phone acts
 * as the user (spec §5).
 */
@Slf4j
@Service
@TbCoreComponent
@RequiredArgsConstructor
public class VoiceCallbackService {

    /** A result can follow a call that began just before expiry and that the dialer retried for 10 minutes. */
    static final long RESULT_GRACE_MS = TimeUnit.MINUTES.toMillis(15);
    static final String TELEMETRY_KEY = "voiceCall";
    static final Set<String> OUTCOMES = Set.of("ACKNOWLEDGED", "ACK_DENIED", "ANSWERED", "NO_ANSWER", "BUSY",
            "REJECTED", "FAILED", "SKIPPED");
    private static final Map<String, String> LANGUAGE_NAMES = Map.of("en", "English", "hi", "Hindi");
    private static final Map<String, String> OUTCOME_TEXT = Map.of(
            "ACKNOWLEDGED", "acknowledged",
            "ACK_DENIED", "acknowledgement refused",
            "ANSWERED", "answered, not acknowledged",
            "NO_ANSWER", "no answer",
            "BUSY", "busy",
            "REJECTED", "rejected",
            "FAILED", "failed");

    private final VoiceCallTokens tokens;
    private final AlarmService alarmService;
    private final UserAuthDetailsCache userAuthDetailsCache;
    private final UserPermissionsService userPermissionsService;
    private final AccessControlService accessControlService;
    private final TbAlarmService tbAlarmService;
    private final TbAlarmCommentService tbAlarmCommentService;
    private final TelemetrySubscriptionService telemetryService;

    public record Precheck(boolean proceed, String reason) {
    }

    public record Ack(boolean acknowledged, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallResult(String token, String callId, String outcome, int sipStatus, int ringSec, int talkSec,
                             boolean speechFallback, long endedAt) {

        /** Spring prints a request body in full at TRACE, so the token, a live credential, stays out of it. */
        @Override
        public String toString() {
            return "CallResult[callId=" + callId + ", outcome=" + outcome + ", sipStatus=" + sipStatus
                    + ", ringSec=" + ringSec + ", talkSec=" + talkSec + ", speechFallback=" + speechFallback
                    + ", endedAt=" + endedAt + "]";
        }
    }

    /** Whether the call is still needed. Only a forged token is an error; everything else is an answer. */
    public Precheck precheck(String token) throws ThingsboardException {
        VoiceCallClaims claims = tokens.verify(token);
        if (tokens.isExpired(claims, 0)) {
            return new Precheck(false, "expired");
        }
        if (claims.alarmId() == null) {
            return new Precheck(true, null);
        }
        Alarm alarm = findAlarm(claims);
        if (alarm == null || findUser(claims) == null) {
            return new Precheck(false, "not-found"); // the alarm, or the person called, is gone
        }
        if (alarm.isAcknowledged()) {
            return new Precheck(false, "acknowledged");
        }
        if (alarm.isCleared()) {
            return new Precheck(false, "cleared");
        }
        return new Precheck(true, null);
    }

    /**
     * "1" was pressed. Acknowledges as the called user, with the same check AlarmController.ackAlarm
     * makes for them. The permission check comes before "already acknowledged", so a user who may not
     * acknowledge never hears that the alarm is acknowledged. (Precheck tells the token holder the
     * alarm's state anyway.)
     */
    public Ack ack(String token) throws ThingsboardException {
        VoiceCallClaims claims = tokens.verify(token);
        if (tokens.isExpired(claims, 0)) {
            return new Ack(false, "expired");
        }
        if (claims.alarmId() == null) {
            return new Ack(false, "not-an-alarm");
        }
        Alarm alarm = findAlarm(claims);
        if (alarm == null) {
            return new Ack(false, "not-found");
        }
        UserAuthDetails details = findUser(claims);
        Authority authority = details == null ? null : details.user().getAuthority();
        if (details == null || !details.credentialsEnabled()
                || (authority != Authority.TENANT_ADMIN && authority != Authority.CUSTOMER_USER)) {
            return refused(alarm, "an unknown or disabled user", "user-disabled");
        }
        SecurityUser actor = actorFor(details.user());
        if (actor == null || !permitted(actor, alarm)) {
            return refused(alarm, details.user().getTitle(), "permission");
        }
        if (alarm.isAcknowledged()) {
            return new Ack(true, "already-acknowledged");
        }
        try {
            tbAlarmService.ack(alarm, actor);
        } catch (ThingsboardException e) {
            Alarm now = findAlarm(claims);
            if (now == null) {
                return new Ack(false, "not-found"); // deleted while we were acknowledging it
            }
            if (now.isAcknowledged()) {
                return new Ack(true, "already-acknowledged"); // the web UI or another call got there first
            }
            throw e;
        }
        return new Ack(true, null);
    }

    /**
     * Records a finished call. Telemetry goes first: it is keyed by the call's end time, so a retry
     * overwrites it, and a failed write is an error the dialer retries. The comment goes last, so a
     * failed write leaves none behind. Delivery is at-least-once: a response lost after success makes
     * the dialer retry, and the retry adds the comment a second time.
     */
    public void result(CallResult result) throws Exception {
        VoiceCallClaims claims = tokens.verify(result.token());
        if (tokens.isExpired(claims, RESULT_GRACE_MS)) {
            throw new ThingsboardException("Voice call token expired", ThingsboardErrorCode.AUTHENTICATION);
        }
        if (result.outcome() == null || !OUTCOMES.contains(result.outcome())) {
            throw new ThingsboardException("Unknown call outcome", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        Alarm alarm = claims.alarmId() == null ? null : findAlarm(claims);
        if (alarm == null) {
            log.info("[{}] Voice call to user {} (request {}), no alarm to record it on: {}",
                    claims.tenantId(), claims.userId(), claims.requestId(), result.outcome());
            return;
        }
        UserAuthDetails details = findUser(claims);
        String name = details == null ? "a deleted user" : details.user().getTitle();
        String phoneLast4 = details == null ? "" : lastFour(details.user().getPhone());
        String language = Objects.toString(claims.language(), "en");

        ObjectNode call = JacksonUtil.newObjectNode()
                .put("alarmId", alarm.getUuidId().toString())
                .put("alarmType", alarm.getType())
                .put("userId", claims.userId().toString())
                .put("userName", name)
                .put("phoneLast4", phoneLast4)
                .put("language", language)
                .put("outcome", result.outcome())
                .put("sipStatus", result.sipStatus())
                .put("ringSec", result.ringSec())
                .put("talkSec", result.talkSec())
                .put("speechFallback", result.speechFallback())
                .put("languageFallback", claims.languageFallback());
        long now = System.currentTimeMillis();
        boolean plausible = result.endedAt() >= now - TimeUnit.DAYS.toMillis(1) && result.endedAt() <= now + TimeUnit.MINUTES.toMillis(1);
        SettableFuture<Void> saved = SettableFuture.create();
        telemetryService.saveTimeseries(TimeseriesSaveRequest.builder()
                .tenantId(claims.tenant())
                .customerId(alarm.getCustomerId())
                .entityId(alarm.getOriginator())
                .entry(new BasicTsKvEntry(plausible ? result.endedAt() : now, new JsonDataEntry(TELEMETRY_KEY, JacksonUtil.toString(call))))
                .future(saved)
                .build());
        saved.get(10, TimeUnit.SECONDS);

        if (!"SKIPPED".equals(result.outcome())) {
            comment(alarm, "Voice call to " + name + (phoneLast4.isEmpty() ? "" : " (…" + phoneLast4 + ")") + ", "
                    + LANGUAGE_NAMES.getOrDefault(language, language)
                    + (claims.languageFallback() ? " (translation missing)" : "")
                    + ": " + OUTCOME_TEXT.get(result.outcome()));
        }
    }

    private Ack refused(Alarm alarm, String who, String reason) throws ThingsboardException {
        comment(alarm, "Voice acknowledgement by " + who + " refused (" + reason + ")");
        return new Ack(false, reason);
    }

    /** A system comment without an author: the alarm's comment list shows its text. */
    private void comment(Alarm alarm, String text) throws ThingsboardException {
        tbAlarmCommentService.saveAlarmComment(alarm, AlarmComment.builder()
                .alarmId(alarm.getId())
                .type(AlarmCommentType.SYSTEM)
                .comment(JacksonUtil.newObjectNode().put("text", text))
                .build(), null);
    }

    /** The alarm the token names, if it is still in the token's tenant. TB DAOs ignore their tenant argument. */
    private Alarm findAlarm(VoiceCallClaims claims) {
        Alarm alarm = alarmService.findAlarmById(claims.tenant(), claims.alarm());
        return alarm != null && claims.tenant().equals(alarm.getTenantId()) ? alarm : null;
    }

    /** The user the token names, if they still exist in the token's tenant (see findAlarm). */
    private UserAuthDetails findUser(VoiceCallClaims claims) {
        UserAuthDetails details = userAuthDetailsCache.getUserAuthDetails(claims.tenant(), claims.user());
        return details != null && details.user() != null && claims.tenant().equals(details.user().getTenantId()) ? details : null;
    }

    /**
     * The called user as a security principal, built the way JwtTokenFactory and the API-key login
     * build one. Without merged permissions a role-restricted user would act with full access, so a
     * failure to load them refuses the acknowledgement.
     */
    private SecurityUser actorFor(User user) {
        SecurityUser actor = new SecurityUser(user, true, new UserPrincipal(UserPrincipal.Type.USER_NAME, user.getEmail()));
        try {
            actor.setUserPermissions(userPermissionsService.getMergedPermissions(actor));
            return actor;
        } catch (RuntimeException e) {
            log.warn("[{}] Could not load the permissions of user {}: {}", user.getTenantId(), user.getId(), e.toString());
            return null;
        }
    }

    private boolean permitted(SecurityUser actor, Alarm alarm) {
        try {
            return accessControlService.hasPermission(actor, Resource.ALARM, Operation.WRITE, alarm.getId(), alarm);
        } catch (ThingsboardException e) {
            return false;
        }
    }

    private static String lastFour(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

}
