// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.InferrixControllerClient;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.FingerprintCapturingTrustManager;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Every HTTP call the platform makes to a gateway passes through here.
 *
 * <p>The TLS model is the controller's, reused rather than reimplemented: the certificate is pinned
 * to the fingerprint captured at adoption and hostname verification is off, because these devices
 * sit on private networks under addresses no certificate can meaningfully assert. See
 * {@link InferrixControllerClient} for why {@code HostnameVerificationPolicy.CLIENT} is the part
 * that actually disables it.
 *
 * <p>Since stack 5.1.0 a gateway generates its own per-host key pair on first boot, so a pin now
 * authenticates something real. Before that release every deployment served the same committed
 * keystore, whose private half is public — pinning one of those proves nothing, which is why
 * adoption refuses the known-default fingerprint rather than trusting it.
 */
@Service
@TbCoreComponent
@Slf4j
public class InferrixGatewayClient {

    /**
     * The controller's cap is 2 because its firmware serves exactly two clients. A gateway is an
     * ordinary Jetty server, so that limit would throttle us for no reason — but it is not
     * unlimited either, since a wide fan-out from several platform nodes is how a small edge box
     * gets knocked over. Eight is a deliberate middle, not an inherited constant.
     */
    private static final int MAX_CONCURRENT_PER_DEVICE = 8;
    private static final long PERMIT_WAIT_SECONDS = 30;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How much of a gateway's answer the platform will hold in memory.
     *
     * <p>Characters, not bytes — that is what {@code EntityUtils} counts. Ten million is far beyond
     * any configuration document the stack serves and far below what would threaten a shared node.
     */
    private static final int MAX_RESPONSE_CHARS = 10_000_000;

    private final Cache<String, Semaphore> deviceLocks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * Buys an access token with the API token issued on the gateway (stack ask A4).
     *
     * <p>Deliberately not a proxied call: {@code /v2/auth/**} is excluded from
     * {@link InferrixGatewayRoutes} so that nothing driven by a browser can ever spend our
     * {@code client_secret}. The exchange still has to happen, so it happens here and only here.
     *
     * <p>RFC 6749 §4.4 — and note the body is <b>form-encoded, not JSON</b>. It is the one call in
     * this client that is; sending JSON is answered 415, not 401, so a failure here is a wiring
     * mistake and never a rejected credential (probed live against stack 5.1.0, 2026-09-23).
     */
    public GatewayToken exchangeToken(String baseUrl, String fingerprint,
                                      String clientId, String clientSecret) throws IOException {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

        ClassicHttpRequest request = ClassicRequestBuilder.post()
                .setUri(baseUrl + InferrixGatewayRoutes.BASE + "/v2/auth/oauth/token")
                .setEntity(new StringEntity(form, ContentType.APPLICATION_FORM_URLENCODED))
                .build();

        GatewayResponse response = send(baseUrl, new FingerprintCapturingTrustManager(fingerprint), request);
        if (response.statusCode() != 200) {
            throw new IOException("The gateway refused the API token: HTTP " + response.statusCode());
        }
        JsonNode body = JacksonUtil.toJsonNode(response.body());
        if (body == null || !body.hasNonNull("access_token")) {
            throw new IOException("The gateway returned no access_token");
        }
        // expires_in is advisory; a gateway that omits it gets the stack's own 30-minute default.
        long expiresIn = body.hasNonNull("expires_in") ? body.get("expires_in").asLong() : 1800L;
        return new GatewayToken(body.get("access_token").asText(), expiresIn);
    }

    /**
     * @param path  an allowlisted resource path <em>without</em> the {@code /rest} prefix, which is
     *              added here so no caller has to remember it
     * @param query the RQL expression, already built by {@link InferrixGatewayRql}, or {@code null}
     *              for none. It is kept apart from {@code path} all the way down because
     *              {@link InferrixGatewayRoutes#isAllowed} refuses a path containing {@code ?}:
     *              joining the two any earlier would either fail that check or move it off the
     *              string actually sent.
     */
    public GatewayResponse call(String baseUrl, String fingerprint, String method, String path,
                                String query, String jwt, String body) throws IOException {
        ClassicRequestBuilder builder = ClassicRequestBuilder.create(method.toUpperCase(java.util.Locale.ROOT))
                .setUri(requestUri(baseUrl, path, query))
                .setHeader("Authorization", "Bearer " + jwt);
        if (body != null && !body.isEmpty()) {
            builder.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        }
        return send(baseUrl, new FingerprintCapturingTrustManager(fingerprint), builder.build());
    }

    /**
     * Contacts a gateway with no pin and reports the certificate it served.
     *
     * <p>Trust on first use: the fingerprint captured here is what every later call is pinned to.
     * That is only meaningful if the device is the sole holder of the private key, which is exactly
     * what adoption checks before storing it — see
     * {@link InferrixGatewayAdoption#isKnownBadCertificate(String)}.
     *
     * <p>Used only at adoption. Every other call supplies a pin.
     */
    public String captureFingerprint(String baseUrl) throws IOException {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);
        ClassicHttpRequest probe = ClassicRequestBuilder.get()
                .setUri(baseUrl + InferrixGatewayRoutes.BASE + "/v2/about")
                .build();
        // The status is deliberately ignored: an unauthenticated read answers 401, and a 401 is a
        // completed handshake and therefore a captured certificate. Only the TLS layer is being
        // interrogated here.
        send(baseUrl, trust, probe);
        String fingerprint = trust.getFingerprint();
        if (fingerprint == null) {
            throw new IOException("The gateway at " + hostOf(baseUrl) + " presented no certificate");
        }
        return fingerprint;
    }

    private GatewayResponse send(String baseUrl, FingerprintCapturingTrustManager trust,
                                 ClassicHttpRequest request) throws IOException {
        String host = hostOf(baseUrl);
        // The same server-side request forgery guard the controller uses, for the same reason: this
        // address is operator-supplied or device-reported, and the platform then dials it.
        InferrixControllerClient.requireReachableControllerAddress(host);

        Semaphore lock = deviceLocks.get(host, h -> new Semaphore(MAX_CONCURRENT_PER_DEVICE));
        boolean acquired = false;
        try {
            acquired = lock.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IOException("Timed out waiting for a free connection slot on " + host);
            }
            try (CloseableHttpClient client = httpClient(trust)) {
                return client.execute(request, response ->
                        new GatewayResponse(response.getCode(), readBody(response.getEntity())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling " + host, e);
        } finally {
            if (acquired) {
                lock.release();
            }
        }
    }

    private static String hostOf(String baseUrl) throws IOException {
        try {
            String host = new URI(baseUrl).getHost();
            if (host == null) {
                throw new IOException("Not a usable gateway address: " + baseUrl);
            }
            return host;
        } catch (URISyntaxException e) {
            throw new IOException("Not a usable gateway address: " + baseUrl, e);
        }
    }

    private static CloseableHttpClient httpClient(FingerprintCapturingTrustManager trust)
            throws IOException {
        TlsSocketStrategy tls = InferrixControllerClient.tlsStrategy(trust);
        BasicHttpClientConnectionManager connectionManager = BasicHttpClientConnectionManager.create(
                RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build());
        connectionManager.setConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                .build());
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(REQUEST_TIMEOUT.toMillis()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setUserAgent("Cortex")
                .build();
    }

    private static String readBody(HttpEntity entity) throws IOException {
        if (entity == null) {
            return "";
        }
        try {
            // Capped. A gateway's list endpoints page, but nothing here can make a device answer
            // within a bound -- a broken or hostile one can stream indefinitely, and the platform
            // would buffer it all into one String on a shared node.
            //
            // Read one character past the cap so that hitting it is detectable. EntityUtils
            // truncates silently, and a truncated JSON body is worse than a refused one: it
            // reaches the browser as a parse error with nothing pointing at the real cause.
            String body = EntityUtils.toString(entity, StandardCharsets.UTF_8, MAX_RESPONSE_CHARS + 1);
            if (body != null && body.length() > MAX_RESPONSE_CHARS) {
                throw new IOException("The gateway's response exceeded " + MAX_RESPONSE_CHARS
                        + " characters and was refused rather than truncated");
            }
            return body;
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Could not read the gateway's response", e);
        }
    }

    /**
     * Joins the base, the {@code /rest} prefix, the path and the query into one URI.
     *
     * <p>Package-visible so the join can be pinned in a test. It looks like string concatenation
     * and is not: the query carries percent escapes that this builder must pass through
     * <em>unchanged</em>. Re-encoding them would turn a searched-for {@code %} into {@code %25},
     * and decoding them would let a value's metacharacter back out into the RQL expression.
     */
    static String requestUri(String baseUrl, String path, String query) {
        String uri = baseUrl + InferrixGatewayRoutes.BASE + path;
        return query == null || query.isEmpty() ? uri : uri + "?" + query;
    }

    /** What a gateway answered. */
    public record GatewayResponse(int statusCode, String body) {
    }

    /** An access token and how long the gateway says it lives. */
    public record GatewayToken(String accessToken, long expiresInSeconds) {
    }
}
