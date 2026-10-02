// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.notification.template.VoiceDeliveryMethodNotificationTemplate;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.Ack;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.CallResult;
import org.thingsboard.server.service.inferrix.voice.VoiceCallbackService.Precheck;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.VoiceDialerException;

import java.util.concurrent.Callable;

/**
 * inferrix-dialer's three callbacks, and the template editor's Translate button.
 *
 * The callbacks are under {@code /api/noauth} because the dialer has no session. The signed per-call
 * token in the body is their only credential (VoiceCallTokens), so nothing here may act on anything
 * the token does not name. They are not on the role read gate's list: they read nothing the token
 * does not already grant.
 */
@Slf4j
@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
public class InferrixVoiceController extends BaseController {

    private final VoiceCallbackService callbacks;
    private final VoiceDialerClient dialer;

    @Value("${inferrix.voice.enabled:false}")
    private boolean enabled;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenRequest(String token) {

        /** Spring prints a request body in full at TRACE, so the token, a live credential, stays out of it. */
        @Override
        public String toString() {
            return "TokenRequest[token=(hidden)]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TranslateRequest(String text) {
    }

    @ApiOperation(value = "Check whether a voice call is still needed (precheck)",
            notes = "Called by inferrix-dialer just before it dials. Answers proceed=false when the alarm is "
                    + "already acknowledged or cleared, or the call's token has expired. The signed token in "
                    + "the body is the only credential.")
    @PostMapping("/noauth/inferrix/voice/v1/precheck")
    public Precheck precheck(@RequestBody TokenRequest request) throws ThingsboardException {
        return guarded("precheck", () -> callbacks.precheck(request.token()));
    }

    @ApiOperation(value = "Acknowledge an alarm by phone (ack)",
            notes = "Called by inferrix-dialer when the called person presses 1. Acknowledges the alarm as "
                    + "that person, with that person's permissions, exactly as the web UI would.")
    @PostMapping("/noauth/inferrix/voice/v1/ack")
    public Ack ack(@RequestBody TokenRequest request) throws ThingsboardException {
        return guarded("ack", () -> callbacks.ack(request.token()));
    }

    @ApiOperation(value = "Report how a voice call ended (result)",
            notes = "Called by inferrix-dialer after every call. Recorded as an alarm comment and as "
                    + "'voiceCall' telemetry on the alarm's originator.")
    @PostMapping("/noauth/inferrix/voice/v1/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@RequestBody CallResult result) throws ThingsboardException {
        guarded("result", () -> {
            callbacks.result(result);
            return null;
        });
    }

    @ApiOperation(value = "Translate a voice message (translateVoiceMessage)",
            notes = "Machine-translates the English text of a voice template to Hindi, offline, "
                    + "on this server, for an administrator to review before saving. Nothing is stored.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN')")
    @PostMapping("/inferrix/voice/translate")
    public JsonNode translate(@RequestBody TranslateRequest request) throws ThingsboardException {
        if (!enabled || !dialer.isConfigured()) {
            throw new ThingsboardException("Voice calls are not enabled", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        String text = request.text();
        if (text == null || text.isBlank() || text.length() > VoiceDeliveryMethodNotificationTemplate.MAX_LENGTH) {
            throw new ThingsboardException("The text must be 1 to " + VoiceDeliveryMethodNotificationTemplate.MAX_LENGTH
                    + " characters", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        JsonNode answer;
        try {
            answer = dialer.translate(text, VoiceDeliveryMethodNotificationTemplate.TRANSLATED_LANGUAGES);
        } catch (VoiceDialerException e) {
            throw new ThingsboardException(e.getMessage(), ThingsboardErrorCode.GENERAL);
        }
        // Rebuilt rather than passed through, so only the fields the editor expects ever reach it.
        ObjectNode translations = JacksonUtil.newObjectNode();
        for (String language : VoiceDeliveryMethodNotificationTemplate.TRANSLATED_LANGUAGES) {
            JsonNode translated = answer.path(language);
            if (translated.path("text").isTextual()) {
                translations.putObject(language)
                        .put("text", translated.path("text").asText())
                        .put("placeholdersOk", translated.path("placeholdersOk").asBoolean(false));
            }
        }
        ObjectNode response = JacksonUtil.newObjectNode();
        response.set("translations", translations);
        return response;
    }

    /**
     * The dialer takes a 4xx as final and a 5xx as a failure on this side: it retries a result, calls
     * anyway after a failed precheck and records a failed ack as answered. So only the service's own
     * ThingsboardException may pick the status. BaseController would answer an IllegalArgumentException
     * or DataValidationException with a 400, which drops a result that could not be written, and would put
     * the message of anything else into the body of a 500. Every other exception (an Error still reaches
     * Spring's own 500) is a fixed 5xx, with the detail in the log. The token is never in an exception
     * message, so it is never logged.
     */
    private <T> T guarded(String callback, Callable<T> call) throws ThingsboardException {
        try {
            return call.call();
        } catch (ThingsboardException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Voice {} callback failed", callback, e);
            throw new ThingsboardException("Voice callback failed", ThingsboardErrorCode.GENERAL);
        }
    }

}
