// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The loopback client for inferrix-dialer (CONTRACT.md in that repo). A 202 from {@code /v1/calls}
 * means queued, not answered: what happened on the call comes back later through the result callback.
 */
@Slf4j
@Component
public class VoiceDialerClient {

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .proxy(HttpClient.Builder.NO_PROXY) // the dialer is on this host: never route it through a proxy
            .build();
    private final URI baseUri;
    private final String token;

    public VoiceDialerClient(@Value("${inferrix.voice.dialer_url:http://127.0.0.1:8765}") String url,
                             @Value("${inferrix.voice.dialer_token:}") String token) {
        this.baseUri = parse(url);
        this.token = headerSafe(token);
    }

    public boolean isConfigured() {
        return baseUri != null && StringUtils.isNotBlank(token);
    }

    public void submitCall(CallJob job) {
        HttpResponse<String> response = post("/v1/calls", job, Duration.ofSeconds(5));
        if (response.statusCode() != 202) {
            throw new VoiceDialerException("Voice dialer refused the call (" + response.statusCode() + ")" + reasonOf(response));
        }
    }

    /** The dialer's answer, {@code {"hi": {"text", "placeholdersOk"}}}. Argos on a CPU is slow: allow it a minute. */
    public JsonNode translate(String text, List<String> languages) {
        HttpResponse<String> response = post("/v1/translate", Map.of("text", text, "to", languages), Duration.ofSeconds(60));
        if (response.statusCode() != 200) {
            throw new VoiceDialerException("Voice dialer could not translate (" + response.statusCode() + ")" + reasonOf(response));
        }
        JsonNode answer = json(response.body());
        if (answer == null || !answer.isObject()) {
            throw new VoiceDialerException("Voice dialer answered with something that is not a JSON object");
        }
        return answer;
    }

    private HttpResponse<String> post(String path, Object body, Duration timeout) {
        if (!isConfigured()) {
            throw new VoiceDialerException("Voice dialer is not configured");
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JacksonUtil.toString(body)))
                .build();
        try {
            CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); // bounds the body too, which HttpRequest.timeout does not
            } finally {
                pending.cancel(true); // closes the connection when we gave up; a no-op once the answer is in
            }
        } catch (ExecutionException | TimeoutException | IllegalArgumentException e) {
            log.warn("Voice dialer at {} is unreachable: {}", baseUri, e.toString());
            throw new VoiceDialerException("Voice dialer is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceDialerException("Interrupted while calling the voice dialer");
        }
    }

    private static String reasonOf(HttpResponse<String> response) {
        JsonNode body = json(response.body());
        JsonNode error = body == null ? null : body.get("error");
        return error != null && error.isTextual() ? ": " + StringUtils.abbreviate(error.asText(), 200) : "";
    }

    private static JsonNode json(String body) {
        try {
            return JacksonUtil.toJsonNode(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A bad URL leaves voice unconfigured rather than stopping the platform from booting. */
    private static URI parse(String url) {
        try {
            URI uri = URI.create(StringUtils.trimToEmpty(url));
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        log.error("inferrix.voice.dialer_url is not an http(s) URL; voice calls are unavailable");
        return null;
    }

    /**
     * The JDK refuses some header values with an exception that quotes the value, which would put the
     * token under Sent. A token that is not visible ASCII leaves voice unconfigured instead.
     */
    private static String headerSafe(String token) {
        if (token == null || token.matches("[\\x21-\\x7e]*")) {
            return token;
        }
        log.error("inferrix.voice.dialer_token has a space, control or non-ASCII character; voice calls are unavailable");
        return null;
    }

    public record CallJob(String callId, String to, String language, String text, boolean ackRequired,
                          String token, String callbackBaseUrl) {

        /** Without the token: a live credential until it expires, so no log line may carry it. */
        @Override
        public String toString() {
            return "CallJob[callId=" + callId + ", to=" + to + ", language=" + language + ", text=" + text
                    + ", ackRequired=" + ackRequired + ", callbackBaseUrl=" + callbackBaseUrl + "]";
        }
    }

    public static class VoiceDialerException extends RuntimeException {
        public VoiceDialerException(String message) {
            super(message);
        }
    }

}
