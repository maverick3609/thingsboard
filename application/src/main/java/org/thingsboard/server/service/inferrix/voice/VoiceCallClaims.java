// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;

import java.util.UUID;

/**
 * What a call token says: who is called, about which alarm, in which language. The keys are short
 * because the token travels in every callback. {@code alarmId} is null for a call that is not about
 * an alarm; {@code languageFallback} says the English text was used because the user's language had
 * no translation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VoiceCallClaims(@JsonProperty("v") int version,
                              @JsonProperty("tid") UUID tenantId,
                              @JsonProperty("rid") UUID requestId,
                              @JsonProperty("uid") UUID userId,
                              @JsonProperty("aid") UUID alarmId,
                              @JsonProperty("lang") String language,
                              @JsonProperty("lf") boolean languageFallback,
                              @JsonProperty("exp") long expiresAt) {

    public static final int VERSION = 1;

    public static VoiceCallClaims of(TenantId tenantId, NotificationRequestId requestId, UserId userId,
                                     AlarmId alarmId, String language, boolean languageFallback) {
        return new VoiceCallClaims(VERSION, tenantId.getId(), requestId == null ? null : requestId.getId(),
                userId.getId(), alarmId == null ? null : alarmId.getId(), language, languageFallback, 0);
    }

    VoiceCallClaims withExpiry(long expiresAt) {
        return new VoiceCallClaims(version, tenantId, requestId, userId, alarmId, language, languageFallback, expiresAt);
    }

    TenantId tenant() {
        return TenantId.fromUUID(tenantId);
    }

    UserId user() {
        return new UserId(userId);
    }

    AlarmId alarm() {
        return alarmId == null ? null : new AlarmId(alarmId);
    }

}
