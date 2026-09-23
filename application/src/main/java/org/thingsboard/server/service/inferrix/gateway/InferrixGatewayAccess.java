// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs a REST call against an adopted gateway on behalf of a platform user.
 *
 * <p>The user never sees or supplies the gateway's credentials. Since stack 5.1.0 that credential
 * is an <b>API token</b> rather than a password: an operator issues one on the gateway, binds it to
 * whichever account they choose, and pastes the {@code client_id}/{@code client_secret} pair into
 * Cortex at adoption. Cortex seals the pair and exchanges it for a short-lived JWT when it needs
 * one.
 *
 * <p>Three properties follow, and each is load-bearing somewhere below:
 *
 * <ul>
 *   <li><b>No refresh cookie exists.</b> RFC 6749 §4.4.3 forbids issuing one for the
 *       {@code client_credentials} grant and the gateway honours that, so the rotating-cookie
 *       reuse-detection hazard — where a clustered Cortex revokes its own access — is designed out
 *       rather than avoided by discipline.</li>
 *   <li><b>The token need not be an administrator.</b> With stack ask A5 the bound account can hold
 *       only {@code permissionGatewayConfiguration}. A {@code 403} is therefore an expected,
 *       ordinary answer on the routes A5 did not widen, not a sign of a bad credential.</li>
 *   <li><b>The JWT is never persisted.</b> The controller stores its bearer token because the
 *       device keeps exactly one and a fresh login revokes the previous. A gateway has no such
 *       constraint, so the token lives in memory only: one less copy of a credential at rest.</li>
 * </ul>
 *
 * <p><b>Why the cache matters more than it looks.</b> The gateway's login limiter is per-IP, burst
 * 5, refilling one token per minute — and an entire Cortex cluster presents as one address. An
 * exchange per call would not be slow, it would be a fleet-wide lockout.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixGatewayAccess {

    public static final String CLIENT_ID = "gwClientId";
    public static final String CLIENT_SECRET = "gwClientSecret";
    public static final String CERT_FINGERPRINT = "gwCertFingerprint";
    public static final String MANAGEMENT_ADDRESS = "gwManagementAddress";
    public static final String MANAGEMENT_PORT = "gwManagementPort";

    /**
     * The gateway's own key, published over MQTT (stack ask A3) — not ours, so it carries no
     * {@code gw} prefix. Read it as it arrives rather than renaming it.
     */
    public static final String REPORTED_ADDRESS = "managementAddress";

    /**
     * Read by explicit list. Never {@code findAll}: a bare attribute read would hand the sealed
     * credential to whatever is on the other end of the request.
     */
    private static final List<String> SERVER_KEYS =
            List.of(CLIENT_ID, CLIENT_SECRET, CERT_FINGERPRINT, MANAGEMENT_ADDRESS, MANAGEMENT_PORT);
    private static final List<String> CLIENT_KEYS = List.of(REPORTED_ADDRESS);

    private static final int DEFAULT_PORT = 443;

    /**
     * A host is a hostname, an IPv4 literal, or a bracketed IPv6 literal — and nothing else.
     *
     * <p>This is a security control, not input tidying. The value arrives from an attribute that an
     * operator sets, or that the <em>device itself</em> reports over MQTT, and it is then composed
     * into a URL. A host of {@code gw.local/rest/v2/script/eval#} composes to
     * {@code https://gw.local/rest/v2/script/eval#:443/rest/v2/about}, which Java parses as host
     * {@code gw.local} and path {@code /rest/v2/script/eval} — the allowlist approves
     * {@code /v2/about} and the gateway receives the script-evaluation endpoint. The allowlisted
     * path is demoted to a discarded fragment. A {@code ?} does the same by demoting it to a query
     * string.
     *
     * <p>Two things that do <b>not</b> fix this, both tried: the SSRF guard passes, because
     * {@code URI.getHost()} returns the clean {@code gw.local} and reachability was never the
     * problem; and {@code new URI(scheme, null, host, port, null, null, null)} does <b>not</b>
     * reject such a host — it encodes it and yields the same injected path, so it fails silently
     * while looking like a fix. The authority has to be validated explicitly, here, before any
     * string is built from it.
     */
    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
            "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "|\\[[0-9A-Fa-f:]{2,45}\\]");

    /**
     * What the probe asks for.
     *
     * <p>Chosen for three properties at once: it is on the allowlist, it is readable by a
     * non-administrator (the stack's own javadoc says any authenticated user may read it), and it
     * is the only identity source the stack has — there is no version or uptime endpoint besides
     * it — so the probe answers "is it there" and "what is it" in one call.
     */
    public static final String PROBE_PATH = "/v2/about";

    /** Re-exchange a little before expiry, so a call cannot start with a token that ends mid-flight. */
    private static final long EXPIRY_MARGIN_MILLIS = 60_000L;

    /** Serialises token exchange per device, so a burst of calls costs one hit on the limiter. */
    private final ConcurrentMap<DeviceId, Object> exchangeLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<DeviceId, CachedToken> tokens = new ConcurrentHashMap<>();

    private final InferrixGatewayClient client;
    private final InferrixSecretCodec secretCodec;
    private final AttributesService attributesService;

    /**
     * Asks the gateway whether it is there, and turns every way that can fail into a reason.
     *
     * <p>Never throws. The endpoint behind this exists to answer exactly one question, so an
     * exception would make that question unanswerable — and the interesting cases are precisely
     * the failures: a rejected token, an under-privileged one, a changed certificate and a gateway
     * that was never adopted are four different problems in four different places, none of them
     * "the network is down".
     */
    public InferrixGatewayReachability probe(TenantId tenantId, DeviceId deviceId) {
        try {
            return InferrixGatewayReachability.of(
                    call(tenantId, deviceId, "GET", PROBE_PATH, null, null));
        } catch (Exception e) {
            return InferrixGatewayReachability.of(e);
        }
    }

    /**
     * Drops the cached JWT for one device.
     *
     * <p>Called when a gateway is re-adopted, since the credential behind the cached token may have
     * been replaced. Not strictly required — a stale token earns a 401 and {@link #call} exchanges
     * again — but only on this node, and only at the cost of one failed call per node.
     */
    public void forget(DeviceId deviceId) {
        tokens.remove(deviceId);
    }

    /**
     * Performs the call, exchanging the API token again once if the gateway rejects the JWT.
     *
     * @param path  an allowlisted resource path with no query string
     * @param query the RQL expression, or {@code null} for none. It arrives as its own argument
     *              rather than appended to {@code path} because the gateway reads a raw query
     *              string as RQL on every verb, so it must be platform-constructed — see
     *              {@link InferrixGatewayRql} — and because {@link InferrixGatewayRoutes#isAllowed}
     *              refuses a path containing {@code ?}, which is what keeps the allowlist judging
     *              the same string the two halves agree on.
     */
    public GatewayResponse call(TenantId tenantId, DeviceId deviceId, String method, String path,
                                String query, String body) throws Exception {
        // Enforced here rather than only in the REST controller because this is the single
        // chokepoint every device-bound call passes through; an endpoint added later that forgets
        // the check is still covered.
        if (!InferrixGatewayRoutes.isAllowed(method, path)) {
            throw new IllegalArgumentException("Not a forwardable gateway route: " + method + " " + path);
        }
        if (!secretCodec.isConfigured()) {
            throw new IllegalStateException(
                    "inferrix.controller.credentials_key is not set, so gateway credentials cannot be"
                            + " opened. Refusing rather than falling back to plaintext.");
        }

        Credentials credentials = load(tenantId, deviceId);
        GatewayResponse response = client.call(credentials.baseUrl(), credentials.fingerprint(),
                method, path, query, token(deviceId, credentials), body);

        // A 403 means the token is valid and under-privileged -- the expected answer for a
        // non-admin service account on the routes stack ask A5 did not widen. Exchanging again
        // would spend the limiter to obtain an identically under-privileged token.
        if (response.statusCode() != 401) {
            return response;
        }

        String fresh;
        synchronized (exchangeLocks.computeIfAbsent(deviceId, id -> new Object())) {
            tokens.remove(deviceId);
            fresh = token(deviceId, credentials);
        }
        // Exactly once. A genuinely wrong client_secret must surface as a 401 rather than become an
        // exchange loop against a limiter that refills one token per minute.
        return client.call(credentials.baseUrl(), credentials.fingerprint(), method, path, query,
                fresh, body);
    }

    private String token(DeviceId deviceId, Credentials credentials) throws Exception {
        CachedToken cached = tokens.get(deviceId);
        if (cached != null && cached.isLive()) {
            return cached.jwt();
        }
        synchronized (exchangeLocks.computeIfAbsent(deviceId, id -> new Object())) {
            CachedToken recheck = tokens.get(deviceId);
            if (recheck != null && recheck.isLive()) {
                return recheck.jwt();
            }
            GatewayToken issued = client.exchangeToken(credentials.baseUrl(), credentials.fingerprint(),
                    credentials.clientId(), credentials.clientSecret());
            CachedToken fresh = new CachedToken(issued.accessToken(),
                    System.currentTimeMillis() + (issued.expiresInSeconds() * 1000L));
            tokens.put(deviceId, fresh);
            return fresh.jwt();
        }
    }

    private Credentials load(TenantId tenantId, DeviceId deviceId) throws Exception {
        Map<String, AttributeKvEntry> server = attributesService
                .find(tenantId, deviceId, AttributeScope.SERVER_SCOPE, SERVER_KEYS).get().stream()
                .collect(Collectors.toMap(AttributeKvEntry::getKey, Function.identity(), (a, b) -> a));
        Map<String, AttributeKvEntry> reported = attributesService
                .find(tenantId, deviceId, AttributeScope.CLIENT_SCOPE, CLIENT_KEYS).get().stream()
                .collect(Collectors.toMap(AttributeKvEntry::getKey, Function.identity(), (a, b) -> a));

        // The operator's address wins. The gateway's own value cannot serve a NAT'd deployment and
        // reports nothing at all for loopback -- the stack says as much itself -- so it is the
        // fallback, not the preference.
        String host = string(server, MANAGEMENT_ADDRESS)
                .or(() -> string(reported, REPORTED_ADDRESS))
                .orElseThrow(() -> new GatewayUnreachableException("NO_ADDRESS",
                        "No management address is set for this gateway, and it has reported none."));

        // A3 reports a bare address: no scheme, no port. Composing the URL is our job -- and since
        // this value is operator- or device-supplied, validating it is the other half of that job.
        if (!HOST.matcher(host).matches()) {
            throw new GatewayUnreachableException("BAD_ADDRESS",
                    "The management address is not a bare host name or IP address.");
        }
        long port = Optional.ofNullable(server.get(MANAGEMENT_PORT))
                .flatMap(AttributeKvEntry::getLongValue)
                .orElse((long) DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            throw new GatewayUnreachableException("BAD_ADDRESS",
                    "The management port is outside the valid range.");
        }

        String clientId = string(server, CLIENT_ID)
                .orElseThrow(() -> new GatewayUnreachableException("NO_CREDENTIAL",
                        "This gateway has no API token; adopt it before configuring it."));
        String clientSecret = string(server, CLIENT_SECRET)
                .orElseThrow(() -> new GatewayUnreachableException("NO_CREDENTIAL",
                        "This gateway has no API token secret; adopt it before configuring it."));

        // Never null. A null pin does not mean "nothing to compare against" -- it means
        // FingerprintCapturingTrustManager skips the comparison, and with the hostname
        // verification this feature necessarily disables, that is any certificate from any host,
        // accepted silently. Anyone holding WRITE_ATTRIBUTES on the device can delete this
        // attribute and repoint the address one, at which point the platform would open the sealed
        // credential and post it to them. The sibling controller access layer refuses the same
        // way; this dropped it.
        String fingerprint = string(server, CERT_FINGERPRINT)
                .orElseThrow(() -> new GatewayUnreachableException("NO_CREDENTIAL",
                        "This gateway has no pinned certificate; adopt it before configuring it."));

        return new Credentials("https://" + host + ":" + port,
                fingerprint,
                secretCodec.decrypt(clientId),
                secretCodec.decrypt(clientSecret));
    }

    private static Optional<String> string(Map<String, AttributeKvEntry> attributes, String key) {
        return Optional.ofNullable(attributes.get(key))
                .flatMap(AttributeKvEntry::getStrValue)
                .filter(value -> !value.isBlank());
    }

    private record Credentials(String baseUrl, String fingerprint, String clientId, String clientSecret) {
    }

    private record CachedToken(String jwt, long expiresAtMillis) {
        boolean isLive() {
            return System.currentTimeMillis() < expiresAtMillis - EXPIRY_MARGIN_MILLIS;
        }
    }

    /**
     * The gateway cannot be reached, and why. The reason is surfaced to the operator verbatim:
     * someone who cannot tell a missing address from a missing credential cannot fix either.
     */
    public static class GatewayUnreachableException extends RuntimeException {

        private final transient String reason;

        public GatewayUnreachableException(String reason, String message) {
            super(reason + ": " + message);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
