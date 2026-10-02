// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.CallJob;
import org.thingsboard.server.service.inferrix.voice.VoiceDialerClient.VoiceDialerException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceDialerClientTest {

    private static final CallJob JOB = new CallJob("c1", "+96891234567", "hi", "अलार्म", true, "tok", "http://127.0.0.1:8080");

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 202;
    private volatile String answer = "{\"callId\":\"c1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private VoiceDialerClient client() {
        return new VoiceDialerClient("http://127.0.0.1:" + server.getAddress().getPort(), "dialer-token");
    }

    @Test
    void submitsTheJobWithTheBearerToken() {
        client().submitCall(JOB);

        assertThat(authorization.get()).isEqualTo("Bearer dialer-token");
        JsonNode sent = JacksonUtil.toJsonNode(body.get());
        assertThat(sent.get("callId").asText()).isEqualTo("c1");
        assertThat(sent.get("to").asText()).isEqualTo("+96891234567");
        assertThat(sent.get("language").asText()).isEqualTo("hi");
        assertThat(sent.get("text").asText()).isEqualTo("अलार्म");
        assertThat(sent.get("ackRequired").asBoolean()).isTrue();
        assertThat(sent.get("token").asText()).isEqualTo("tok");
        assertThat(sent.get("callbackBaseUrl").asText()).isEqualTo("http://127.0.0.1:8080");
    }

    @Test
    void aRefusalCarriesTheDialersReason() {
        status = 400;
        answer = "{\"error\":\"number not allowed\"}";

        assertThatThrownBy(() -> client().submitCall(JOB))
                .isInstanceOf(VoiceDialerException.class)
                .hasMessage("Voice dialer refused the call (400): number not allowed");

        // the reason is capped, and an answer that holds no reason adds none
        answer = "{\"error\":\"" + "x".repeat(1000) + "\"}";
        assertThatThrownBy(() -> client().submitCall(JOB))
                .hasMessage("Voice dialer refused the call (400): " + "x".repeat(197) + "...");
        for (String hostile : List.of("not json", "{\"error\":", "[]", "null", "", "{\"error\":123}", "{\"error\":{\"a\":1}}")) {
            answer = hostile;
            assertThatThrownBy(() -> client().submitCall(JOB))
                    .isInstanceOf(VoiceDialerException.class)
                    .hasMessage("Voice dialer refused the call (400)");
        }
    }

    @Test
    void anUnreachableDialerIsAnError() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        VoiceDialerClient client = new VoiceDialerClient("http://127.0.0.1:" + port, "dialer-token");

        assertThatThrownBy(() -> client.submitCall(JOB))
                .isInstanceOf(VoiceDialerException.class)
                .hasMessage("Voice dialer is unreachable");
    }

    @Test
    void translateReturnsTheDialersAnswer() {
        status = 200;
        answer = "{\"hi\":{\"text\":\"अलार्म\",\"placeholdersOk\":true}}";

        JsonNode translated = client().translate("Alarm", List.of("hi"));

        assertThat(translated.at("/hi/text").asText()).isEqualTo("अलार्म");
        assertThat(JacksonUtil.toJsonNode(body.get()).get("to")).hasSize(1);

        answer = "[]";
        assertThatThrownBy(() -> client().translate("Alarm", List.of("hi"))).isInstanceOf(VoiceDialerException.class);
        answer = ""; // nothing to read is an error too, not a NullPointerException
        assertThatThrownBy(() -> client().translate("Alarm", List.of("hi"))).isInstanceOf(VoiceDialerException.class);

        status = 400;
        answer = "{\"error\":\"language not offered\"}";
        assertThatThrownBy(() -> client().translate("Alarm", List.of("xx")))
                .isInstanceOf(VoiceDialerException.class)
                .hasMessage("Voice dialer could not translate (400): language not offered");
    }

    @Test
    void aMalformedUrlLeavesVoiceUnconfigured() {
        assertThat(new VoiceDialerClient("not a url", "dialer-token").isConfigured()).isFalse();
        assertThat(new VoiceDialerClient("file:///etc/passwd", "dialer-token").isConfigured()).isFalse();
        assertThat(new VoiceDialerClient("http://127.0.0.1:8765", "").isConfigured()).isFalse();
        assertThatThrownBy(() -> new VoiceDialerClient("not a url", "dialer-token").submitCall(JOB))
                .isInstanceOf(VoiceDialerException.class)
                .hasMessage("Voice dialer is not configured");

        // the JDK refuses some header values with an error that quotes the value, which would show the token
        // under Sent, so only visible ASCII (base64, base64url and hex tokens all qualify) is accepted
        assertThat(new VoiceDialerClient("http://127.0.0.1:8765", "Az09+/=_-.!~").isConfigured()).isTrue();
        for (String token : List.of("dialer\r\ntoken", "dialer token", "dialer\u007ftoken", "dialer-tokén", "dialer-टोकन")) {
            VoiceDialerClient client = new VoiceDialerClient("http://127.0.0.1:8765", token);

            assertThatThrownBy(() -> client.submitCall(JOB))
                    .isInstanceOf(VoiceDialerException.class)
                    .hasMessage("Voice dialer is not configured");
            assertThat(client.isConfigured()).isFalse();
        }
    }

}
