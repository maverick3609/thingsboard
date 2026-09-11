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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.ControllerResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Runs a REST call against an adopted controller on behalf of a platform user.
 *
 * <p>The user never sees or supplies the controller's credentials: the bearer token and ownership
 * password are sealed in server-scope attributes and only ever opened here. That is the point of
 * proxying rather than letting the UI talk to the device.
 *
 * <p><b>Token rotation.</b> The controller keeps exactly one active token, and any login — from a
 * second platform node, an engineer's laptop, or a password change — revokes the one before it.
 * Over REST that surfaces as a 401, which is recoverable: log in again with the sealed password,
 * store the new token, and replay the call once. Exactly once, so a genuinely wrong password cannot
 * turn into a login loop against the firmware's doubling brute-force throttle.
 *
 * <p><b>Address.</b> The controller republishes its IP over MQTT whenever DHCP moves it, so the
 * device-reported address is preferred over the one recorded at adoption. That value is
 * device-asserted, but it cannot be used to redirect anything: the certificate pin is checked
 * against the fingerprint captured at adoption, so a call to the wrong host fails the handshake.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixControllerAccess {

    /** Serialises token refresh per device; see {@link #call}. */
    private final ConcurrentMap<DeviceId, Object> refreshLocks = new ConcurrentHashMap<>();

    private final InferrixControllerClient client;
    private final InferrixSecretCodec secretCodec;
    private final AttributesService attributesService;
    private final TelemetrySubscriptionService tsSubService;

    /** Performs the call, refreshing the token once if the controller rejects it. */
    public ControllerResponse call(TenantId tenantId, DeviceId deviceId, String method, String path,
                                     String body) throws Exception {
        Credentials credentials = load(tenantId, deviceId);
        ControllerResponse response = client.call(credentials.host(), credentials.fingerprint(),
                method, path, credentials.token(), body);
        if (response.statusCode() != 401) {
            return response;
        }
        if (credentials.password() == null) {
            throw new IOException("The controller rejected the stored token and no password is held"
                    + " for it, so it cannot be recovered without re-adopting the device");
        }
        // Serialised, and the stored token re-read inside the lock: the controller keeps exactly one
        // active token, so two concurrent refreshes would have the second revoke the first and fail
        // whichever call raced. The second waiter usually finds a working token already stored and
        // never logs in at all.
        String fresh;
        synchronized (refreshLocks.computeIfAbsent(deviceId, id -> new Object())) {
            Credentials reloaded = load(tenantId, deviceId);
            if (!reloaded.token().equals(credentials.token())) {
                fresh = reloaded.token();
            } else {
                log.info("[{}] Controller rejected the stored token; logging in again", deviceId);
                fresh = client.login(credentials.host(), credentials.fingerprint(), credentials.password());
                storeToken(tenantId, deviceId, fresh);
            }
        }
        return client.call(credentials.host(), credentials.fingerprint(), method, path, fresh, body);
    }

    /**
     * Opens the sealed credentials, having first proved the stored token still works.
     *
     * <p>For a long binary upload the token has to be settled up front: a 401 arriving on chunk 200
     * of 400 cannot be recovered by logging in again, because a fresh login revokes the token the
     * upload started under and the device's single-uploader state is already half written. Probing
     * a cheap read first drives {@link #call}'s normal refresh, so the credentials returned here are
     * ones that have just worked.
     */
    Credentials openVerifiedCredentials(TenantId tenantId, DeviceId deviceId, String probePath)
            throws Exception {
        ControllerResponse probe = call(tenantId, deviceId, "GET", probePath, null);
        if (probe.statusCode() != 200) {
            throw new IOException("The controller answered HTTP " + probe.statusCode() + " to "
                    + probePath + ", so an upload cannot be started");
        }
        return load(tenantId, deviceId);
    }

    /** One raw chunk, under credentials already opened by {@link #openVerifiedCredentials}. */
    ControllerResponse callBinary(Credentials credentials, String path, byte[] chunk)
            throws IOException {
        return client.callBinary(credentials.host(), credentials.fingerprint(), path,
                credentials.token(), chunk);
    }

    /** A JSON call under already-opened credentials, so an upload keeps one settled token. */
    ControllerResponse callWith(Credentials credentials, String method, String path, String body)
            throws IOException {
        return client.call(credentials.host(), credentials.fingerprint(), method, path,
                credentials.token(), body);
    }

    Credentials load(TenantId tenantId, DeviceId deviceId) throws Exception {
        Map<String, String> server = readAttributes(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                List.of(InferrixAdoptionService.ATTR_IP, InferrixAdoptionService.ATTR_CERT_FINGERPRINT,
                        InferrixAdoptionService.ATTR_TOKEN, InferrixAdoptionService.ATTR_PASSWORD));
        String fingerprint = server.get(InferrixAdoptionService.ATTR_CERT_FINGERPRINT);
        String sealedToken = server.get(InferrixAdoptionService.ATTR_TOKEN);
        if (fingerprint == null || sealedToken == null) {
            throw new IllegalStateException("This device has not been adopted as an Inferrix controller");
        }
        Map<String, String> client = readAttributes(tenantId, deviceId, AttributeScope.CLIENT_SCOPE,
                List.of("ip"));
        String host = client.getOrDefault("ip", server.get(InferrixAdoptionService.ATTR_IP));
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("No address is known for this controller");
        }
        String sealedPassword = server.get(InferrixAdoptionService.ATTR_PASSWORD);
        return new Credentials(host, fingerprint, secretCodec.decrypt(sealedToken),
                sealedPassword == null ? null : secretCodec.decrypt(sealedPassword));
    }

    private Map<String, String> readAttributes(TenantId tenantId, DeviceId deviceId, AttributeScope scope,
                                               List<String> keys) throws Exception {
        return attributesService.find(tenantId, deviceId, scope, keys).get().stream()
                .filter(entry -> entry.getValueAsString() != null)
                .collect(Collectors.toMap(AttributeKvEntry::getKey, AttributeKvEntry::getValueAsString));
    }

    private void storeToken(TenantId tenantId, DeviceId deviceId, String token) {
        AttributeKvEntry entry = new BaseAttributeKvEntry(
                new StringDataEntry(InferrixAdoptionService.ATTR_TOKEN, secretCodec.encrypt(token)),
                System.currentTimeMillis());
        // Fire-and-forget is acceptable here, unlike at adoption: losing this write only costs
        // another refresh on the next call, because the password it was derived from is still held.
        tsSubService.saveAttributes(AttributesSaveRequest.builder()
                .tenantId(tenantId)
                .entityId(deviceId)
                .scope(AttributeScope.SERVER_SCOPE)
                .entries(List.of(entry))
                .callback(new com.google.common.util.concurrent.FutureCallback<>() {
                    @Override
                    public void onSuccess(Void result) {
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        log.warn("[{}] Failed to store the refreshed controller token", deviceId, t);
                    }
                })
                .build());
    }

    record Credentials(String host, String fingerprint, String token, String password) {
    }

}
