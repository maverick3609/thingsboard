/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.inferrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.queue.util.TbCoreComponent;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Talks to a controller's HTTPS REST API.
 *
 * <p><b>Why the platform proxies instead of the browser calling the device.</b> The firmware serves
 * a per-device self-signed EC certificate with no CA behind it, and sends no CORS headers at all
 * (verified against {@code src/rest/} in the controller repo). A browser cannot be made to accept
 * either. Every device call therefore originates here.
 *
 * <p><b>Trust model (TOFU).</b> There is no CA to validate against, so the certificate is pinned by
 * SHA-256 fingerprint:
 * <ul>
 *   <li>First contact ({@link #fetchInfo}) accepts whatever certificate is served, records its
 *       fingerprint, and cross-checks it against the {@code certFingerprint} the device reports in
 *       the body. A mismatch means the responder is not the device it claims to be and aborts.</li>
 *   <li>Every later call pins to the stored fingerprint and fails closed if it changed.</li>
 * </ul>
 * First contact cannot be authenticated — that is inherent to TOFU and why the firmware
 * documentation says to commission on a bench or a private network.
 *
 * <p><b>Why hostname verification is off.</b> The firmware's certificate carries no X.509 extensions
 * at all, so it has no {@code subjectAltName} — and the platform dials a bare IP. Every standard
 * verifier therefore rejects it outright ("No subject alternative names present") before the pin is
 * ever consulted, which made the whole device-facing REST surface unusable against real hardware.
 * Turning it off costs nothing here: the pin is a SHA-256 of the exact certificate, which is a
 * strictly stronger statement of identity than a name match. A SAN would not even help — the only
 * name to put in one is the device's current IP, which a DHCP move invalidates and whose cert
 * regeneration would break the pin instead.
 *
 * <p>This is also why the client is Apache HttpClient rather than {@code java.net.http.HttpClient}:
 * the JDK client re-forces endpoint identification internally and ignores any attempt to disable it
 * per-client, leaving only a JVM-wide system property that would weaken every other caller in the
 * platform.
 *
 * <p>ponytail: {@code POST /api/v1/attest} would additionally bind the uid to that certificate key
 * by signing {@code SHA-256("infx-attest-v1" || nonce || uid)} — the encoding is now confirmed
 * against hardware (message is {@code CTX || nonce_bytes || uid_ascii}, DER ECDSA-P256). Still not
 * done here because TOFU already pins the key this would authenticate; add it when a device needs
 * to prove itself across a cert change rather than at first contact.
 *
 * <p>Transport quirks handled: HTTPS only on 443, {@code Connection: close} with exactly one request
 * per TCP connection, at most two concurrent clients per device, and a 2048-byte cap on the whole
 * request including headers.
 */
@Component
@TbCoreComponent
@Slf4j
public class InferrixControllerClient {

    /** The firmware accepts two clients at once and makes a third wait; do not be the third. */
    private static final int MAX_CONCURRENT_PER_DEVICE = 2;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long PERMIT_WAIT_SECONDS = 30;

    /** Request line + headers + body must fit in 2048 bytes or the device answers 413. */
    private static final int MAX_REQUEST_BYTES = 2048;
    private static final int HEADER_ALLOWANCE_BYTES = 512;

    /**
     * One permit pair per device address. Bounded and expiring so that probing many addresses cannot
     * grow it without limit; an entry evicted while calls are in flight only costs a briefly wider
     * concurrency window against that one device, never correctness.
     */
    private final Cache<String, Semaphore> deviceLocks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .maximumSize(4096)
            .build();

    /** Unauthenticated identity read that also establishes the pin. */
    public InferrixControllerInfo fetchInfo(String host) throws IOException {
        FingerprintCapturingTrustManager trust = new FingerprintCapturingTrustManager(null);
        ControllerResponse response = send(host, trust, requestBuilder(host, "GET", "/api/v1/info").build());
        requireOk(response, "GET /api/v1/info");
        JsonNode body = parse(response.body());
        String servedFingerprint = trust.getFingerprint();
        String claimedFingerprint = body.path("certFingerprint").asText(null);
        if (servedFingerprint == null) {
            throw new IOException("The controller at " + host + " presented no certificate");
        }
        if (claimedFingerprint == null || !servedFingerprint.equalsIgnoreCase(claimedFingerprint)) {
            // The body is signed by nothing, but it is served over the very certificate whose
            // fingerprint it states. Disagreement means whatever answered is not the device.
            throw new IOException("The controller at " + host + " reports a certificate fingerprint that"
                    + " does not match the certificate it served");
        }
        return new InferrixControllerInfo(body, servedFingerprint);
    }

    /**
     * Claims an unprovisioned controller by setting its ownership password. First caller wins; a
     * device someone else already claimed answers 409.
     */
    public void provision(String host, String fingerprint, String password) throws IOException {
        ControllerResponse response = call(host, fingerprint, "POST", "/api/v1/auth/provision", null,
                JacksonUtil.newObjectNode().put("password", password).toString());
        if (response.statusCode() == 409) {
            throw new InferrixAlreadyProvisionedException(host);
        }
        requireOk(response, "POST /api/v1/auth/provision");
    }

    /** Mints a fresh bearer token. Logging in revokes whatever token was active before. */
    public String login(String host, String fingerprint, String password) throws IOException {
        ControllerResponse response = call(host, fingerprint, "POST", "/api/v1/auth/login", null,
                JacksonUtil.newObjectNode().put("password", password).toString());
        requireOk(response, "POST /api/v1/auth/login");
        String token = parse(response.body()).path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IOException("The controller at " + host + " returned no token");
        }
        return token;
    }

    public void putMqttConfig(String host, String fingerprint, String token, JsonNode config) throws IOException {
        requireOk(call(host, fingerprint, "PUT", "/api/v1/mqtt", token, config.toString()),
                "PUT /api/v1/mqtt");
    }

    public void putIdentity(String host, String fingerprint, String token, JsonNode identity) throws IOException {
        requireOk(call(host, fingerprint, "PUT", "/api/v1/identity", token, identity.toString()),
                "PUT /api/v1/identity");
    }

    /** Generic pinned call, so later phases can reach the rest of the surface without new plumbing. */
    public ControllerResponse call(String host, String fingerprint, String method, String path,
                                   String token, String body) throws IOException {
        int estimated = (body == null ? 0 : body.length()) + path.length() + HEADER_ALLOWANCE_BYTES;
        if (estimated > MAX_REQUEST_BYTES) {
            throw new IOException("Request for " + path + " exceeds the controller's "
                    + MAX_REQUEST_BYTES + "-byte cap on the whole request");
        }
        ClassicRequestBuilder builder = requestBuilder(host, method, path);
        if (body != null) {
            builder.setEntity(body, ContentType.APPLICATION_JSON);
        }
        if (token != null) {
            builder.setHeader("Authorization", "Bearer " + token);
        }
        return send(host, new FingerprintCapturingTrustManager(fingerprint), builder.build());
    }

    /**
     * Posts one raw binary chunk — the firmware and logic upload bodies, which are octet streams
     * rather than JSON.
     *
     * <p>Separate from {@link #call} because a firmware image is not text: routing it through a
     * String would re-encode it and corrupt the image, and the size check has to count bytes rather
     * than characters. {@link #maxChunkBytes} is what a caller must respect to stay inside the
     * device's cap on the whole request.
     */
    public ControllerResponse callBinary(String host, String fingerprint, String path,
                                         String token, byte[] chunk) throws IOException {
        if (chunk.length > maxChunkBytes(path)) {
            throw new IOException("Chunk of " + chunk.length + " bytes exceeds what fits in the"
                    + " controller's " + MAX_REQUEST_BYTES + "-byte request cap for " + path);
        }
        ClassicRequestBuilder builder = requestBuilder(host, "POST", path)
                .setEntity(chunk, ContentType.APPLICATION_OCTET_STREAM);
        if (token != null) {
            builder.setHeader("Authorization", "Bearer " + token);
        }
        return send(host, new FingerprintCapturingTrustManager(fingerprint), builder.build());
    }

    /** Largest chunk body that still leaves room for the request line, headers and the token. */
    public static int maxChunkBytes(String path) {
        return MAX_REQUEST_BYTES - HEADER_ALLOWANCE_BYTES - path.length();
    }

    private ClassicRequestBuilder requestBuilder(String host, String method, String path) {
        return ClassicRequestBuilder.create(method).setUri("https://" + host + path);
    }

    private ControllerResponse send(String host, FingerprintCapturingTrustManager trust,
                                    ClassicHttpRequest request) throws IOException {
        requireReachableControllerAddress(host);
        Semaphore lock = deviceLocks.get(host, h -> new Semaphore(MAX_CONCURRENT_PER_DEVICE));
        boolean acquired = false;
        try {
            acquired = lock.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IOException("Timed out waiting for a free connection slot on " + host);
            }
            // One client per request: the firmware closes the connection after every response, and a
            // fresh client is also what keeps the per-call pin from leaking across devices.
            try (CloseableHttpClient client = httpClient(trust)) {
                return client.execute(request, response -> new ControllerResponse(
                        response.getCode(), readBody(response.getEntity())));
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

    private static String readBody(HttpEntity entity) throws IOException {
        if (entity == null) {
            return "";
        }
        try {
            return EntityUtils.toString(entity, StandardCharsets.UTF_8);
        } catch (ParseException e) {
            throw new IOException("The controller returned a response that could not be read", e);
        }
    }

    /**
     * A client pinned to one device's certificate, with hostname verification switched off for the
     * reasons in the class javadoc.
     *
     * <p>{@link HostnameVerificationPolicy#CLIENT} is what actually disables it: the default policy
     * is {@code BUILTIN}, which defers to the JSSE endpoint identification that rejects a
     * SAN-less certificate no matter which {@link javax.net.ssl.HostnameVerifier} is supplied.
     *
     * <p>Retries and redirects are off — the firmware serves two clients at a time, so a silent
     * retry spends a slot the caller did not ask for, and the device never redirects. Content
     * compression is off and the agent string is short because the 2048-byte request cap counts
     * every header byte.
     */
    private static CloseableHttpClient httpClient(FingerprintCapturingTrustManager trust) throws IOException {
        BasicHttpClientConnectionManager connectionManager = BasicHttpClientConnectionManager.create(
                RegistryBuilder.<TlsSocketStrategy>create().register("https", tlsStrategy(trust)).build());
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
                .disableContentCompression()
                .setUserAgent("Cortex")
                .build();
    }

    /**
     * TLS pinned to one device's certificate, with hostname verification switched off.
     *
     * <p>Package-private so the security decision can be tested directly: the whole point is that a
     * certificate with no {@code subjectAltName} still completes a handshake, while one whose
     * fingerprint does not match the pin still fails.
     */
    static TlsSocketStrategy tlsStrategy(FingerprintCapturingTrustManager trust) throws IOException {
        return ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext(trust))
                .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .buildClassic();
    }

    /**
     * What a controller answered. Mirrors the {@code statusCode()}/{@code body()} shape the callers
     * already use, so the transport underneath can change without touching them.
     */
    public record ControllerResponse(int statusCode, String body) {
    }

    /**
     * The address reaching this point is either operator-supplied or device-reported, and the
     * platform then makes an outbound request to it — a server-side request forgery primitive left
     * open. It cannot be narrowed to a subnet, since reaching arbitrary private LAN addresses is the
     * whole point of the feature. What it can refuse is the two ranges that are never a controller
     * and are exactly what such a probe aims at: the platform's own loopback, and link-local, where
     * cloud instance metadata lives.
     *
     * <p>Enforced here rather than in the callers so that every path is covered — adoption, the
     * proxy, and anything added later.
     */
    static void requireReachableControllerAddress(String host) throws IOException {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve the controller address " + host, e);
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new IOException(host + " is not a usable controller address");
        }
    }

    private static SSLContext sslContext(FingerprintCapturingTrustManager trust) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, new javax.net.ssl.TrustManager[]{trust}, null);
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Failed to build a TLS context for the controller", e);
        }
    }

    private static void requireOk(ControllerResponse response, String what) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException(what + " failed with HTTP " + response.statusCode());
        }
    }

    private static JsonNode parse(String body) throws IOException {
        JsonNode node = JacksonUtil.toJsonNode(body);
        if (node == null || !node.isObject()) {
            throw new IOException("The controller returned a response that is not a JSON object");
        }
        return node;
    }

    static String fingerprintOf(X509Certificate certificate) throws CertificateException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new CertificateException("Failed to fingerprint the controller certificate", e);
        }
    }

    /**
     * Records the served certificate's fingerprint and, when a pin is supplied, refuses anything
     * else. There is no CA in this trust model, so the fingerprint is the whole of it.
     */
    static final class FingerprintCapturingTrustManager implements X509TrustManager {

        private final String expectedFingerprint;
        @Getter
        private volatile String fingerprint;

        FingerprintCapturingTrustManager(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("This trust manager is client-side only");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("The controller presented no certificate");
            }
            String served = fingerprintOf(chain[0]);
            this.fingerprint = served;
            if (expectedFingerprint != null && !expectedFingerprint.equalsIgnoreCase(served)) {
                throw new CertificateException("The controller's certificate fingerprint changed:"
                        + " expected " + expectedFingerprint + " but got " + served);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Identity plus the fingerprint actually served, so the caller pins what it really talked to. */
    public record InferrixControllerInfo(JsonNode body, String certFingerprint) {

        public String uid() {
            return body.path("uid").asText(null);
        }

        public Optional<String> name() {
            String value = body.path("name").asText(null);
            return Optional.ofNullable(value == null || value.isBlank() ? null : value);
        }
    }

    /** Raised when a controller has already been claimed and the caller supplied no password. */
    public static class InferrixAlreadyProvisionedException extends IOException {
        public InferrixAlreadyProvisionedException(String host) {
            super("The controller at " + host + " is already provisioned; supply its existing password to adopt it");
        }
    }

}
