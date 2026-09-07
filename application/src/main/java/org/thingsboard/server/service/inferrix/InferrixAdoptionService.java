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

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.device.profile.MqttDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.settings.AdminSettingsService;
import org.thingsboard.server.common.data.AdminSettings;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.device.TbDeviceService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.google.common.util.concurrent.FutureCallback;
import jakarta.annotation.Nullable;

/**
 * Turns a controller nobody owns into a managed platform device.
 *
 * <p>The flow, and why it is in this order:
 * <ol>
 *   <li><b>Identify.</b> {@code GET /api/v1/info} over TLS, pinning the certificate the device
 *       actually serves and checking it against the fingerprint the device claims.</li>
 *   <li><b>Claim.</b> {@code POST /api/v1/auth/provision} sets the ownership password. First caller
 *       wins — the firmware has no default password, so a device answering 409 already belongs to
 *       someone and the operator must supply that password instead.</li>
 *   <li><b>Log in</b> for a bearer token. The token is what later signs MQTT point writes.</li>
 *   <li><b>Register</b> a platform device with MQTT basic credentials, on a device profile carrying
 *       the Inferrix topic root so the transport recognises the scheme.</li>
 *   <li><b>Point the controller at the broker</b> with those credentials. This is deliberately
 *       last: until the device exists platform-side there is nothing for it to connect as.</li>
 * </ol>
 *
 * <p>Steps 4 and 5 are not atomic and cannot be — one is a database write, the other a call to a
 * device on a network. A failure at step 5 leaves a registered device that is not yet talking, which
 * re-running adoption fixes; the reverse order would leave a controller pointed at credentials that
 * do not exist.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixAdoptionService {

    public static final String PROFILE_NAME = "Inferrix Controller";
    public static final String BROKER_SETTINGS_KEY = "inferrixController";

    static final String ATTR_UID = "controllerUid";
    static final String ATTR_IP = "controllerIp";
    static final String ATTR_CERT_FINGERPRINT = "controllerCertFingerprint";
    static final String ATTR_PASSWORD = "controllerPasswordSealed";
    static final String ATTR_TOKEN = "controllerTokenSealed";

    private static final int GENERATED_PASSWORD_BYTES = 24;
    private static final long ATTRIBUTE_SAVE_TIMEOUT_SECONDS = 30;

    private final InferrixControllerClient client;
    private final InferrixSecretCodec secretCodec;
    /**
     * Optional: the discovery listener is off by default, and adoption must not depend on it —
     * adopting by explicit address is the normal path when the platform is not on the controller's
     * broadcast domain. A required dependency here would fail the whole context on a default boot.
     */
    private final ObjectProvider<InferrixDiscoveryService> discoveryService;
    private final DeviceService deviceService;
    private final DeviceCredentialsService deviceCredentialsService;
    private final DeviceProfileService deviceProfileService;
    private final AttributesService attributesService;
    private final TbDeviceService tbDeviceService;
    private final AdminSettingsService adminSettingsService;
    private final TelemetrySubscriptionService tsSubService;

    private final SecureRandom random = new SecureRandom();

    public Device adopt(TenantId tenantId, User user, InferrixAdoptRequest request) throws Exception {
        if (!secretCodec.isConfigured()) {
            throw new IllegalStateException("inferrix.controller.credentials_key is not set, so controller "
                    + "credentials cannot be stored safely. Generate one with: openssl rand -base64 32");
        }
        String host = resolveHost(tenantId, request);
        var info = client.fetchInfo(host);
        String uid = info.uid();
        if (!InferrixDiscoveryService.isPlausibleUid(uid)) {
            throw new IllegalArgumentException("The controller at " + host + " reported an unusable uid");
        }
        String fingerprint = info.certFingerprint();

        String password = request.password();
        if (password == null || password.isBlank()) {
            password = newPassword();
            try {
                client.provision(host, fingerprint, password);
            } catch (InferrixControllerClient.InferrixAlreadyProvisionedException e) {
                // Someone already claimed this controller — a bench commissioner, another platform,
                // or a previous adoption. There is no way to recover the password from the device
                // (PBKDF2, never returned), so the operator has to supply it.
                throw e;
            }
        }
        String token = client.login(host, fingerprint, password);

        Device device = registerDevice(tenantId, user, uid, request, info);
        saveControllerAttributes(tenantId, device, uid, host, fingerprint, password, token);
        configureBroker(host, fingerprint, token, uid, deviceCredentialsPassword(device));

        if (request.name() != null || request.location() != null) {
            ObjectNode identity = JacksonUtil.newObjectNode();
            if (request.name() != null) {
                identity.put("name", request.name());
            }
            if (request.location() != null) {
                identity.put("location", request.location());
            }
            client.putIdentity(host, fingerprint, token, identity);
        }

        InferrixDiscoveryService discovery = discoveryService.getIfAvailable();
        if (discovery != null) {
            discovery.forget(uid);
        }
        log.info("Adopted Inferrix controller {} at {} as device {}", uid, host, device.getId());
        return device;
    }

    private String resolveHost(TenantId tenantId, InferrixAdoptRequest request) throws IOException {
        InferrixDiscoveryService discovery = discoveryService.getIfAvailable();
        String host;
        if (request.host() != null && !request.host().isBlank()) {
            host = request.host().trim();
            // Adopting by address stays open — that is bench commissioning, where the operator knows
            // the address out of band. What it must not become is a way around assignment: if this
            // address is a controller a system administrator handed to a different tenant, refuse,
            // or whoever adopts first wins regardless of who it was allocated to.
            requireNotAllocatedElsewhere(discovery, host, tenantId);
        } else {
            InferrixControllerSighting sighting = discovery == null
                    ? null : discovery.getSightingFor(request.uid(), tenantId);
            if (sighting == null) {
                // Deliberately the same message whether the uid was never seen or belongs to
                // another tenant: the difference is exactly what a tenant must not learn.
                throw new IllegalArgumentException("No controller " + request.uid()
                        + " is available to this tenant; supply the controller's address explicitly");
            }
            host = sighting.getConnectBackIp();
        }
        return host;
    }

    private void requireNotAllocatedElsewhere(InferrixDiscoveryService discovery, String host, TenantId tenantId) {
        if (discovery == null) {
            return;
        }
        for (InferrixControllerSighting sighting : discovery.getAllSightings()) {
            if (host.equals(sighting.getConnectBackIp())
                    && sighting.getAssignedTenantId() != null
                    && !sighting.getAssignedTenantId().equals(tenantId)) {
                throw new IllegalArgumentException("The controller at " + host
                        + " is allocated to another tenant");
            }
        }
    }

    private Device registerDevice(TenantId tenantId, User user, String uid,
                                  InferrixAdoptRequest request,
                                  InferrixControllerClient.InferrixControllerInfo info) throws Exception {
        DeviceProfile profile = findOrCreateProfile(tenantId);
        String name = deviceName(uid, request, info);
        Device device = deviceService.findDeviceByTenantIdAndName(tenantId, name);
        if (device == null) {
            device = new Device();
            device.setTenantId(tenantId);
            device.setName(name);
        } else {
            // Re-adopting the same controller is fine and idempotent. Landing on an unrelated device
            // that merely shares the name is not: this method replaces the device's credentials and
            // profile, which would silently break whatever was connecting as it.
            requireSameController(tenantId, device, uid);
        }
        device.setType(profile.getName());
        device.setDeviceProfileId(profile.getId());
        if (request.label() != null) {
            device.setLabel(request.label());
        }

        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.MQTT_BASIC);
        BasicMqttCredentials mqttCredentials = new BasicMqttCredentials();
        mqttCredentials.setClientId("infx-" + uid);
        mqttCredentials.setUserName(uid);
        mqttCredentials.setPassword(newPassword());
        credentials.setCredentialsValue(JacksonUtil.toString(mqttCredentials));

        return tbDeviceService.saveDeviceWithCredentials(device, credentials, user);
    }

    private void requireSameController(TenantId tenantId, Device existing, String uid) throws Exception {
        Optional<AttributeKvEntry> recordedUid = attributesService.find(
                tenantId, existing.getId(), AttributeScope.SERVER_SCOPE, ATTR_UID).get();
        String previous = recordedUid.map(AttributeKvEntry::getValueAsString).orElse(null);
        if (!uid.equals(previous)) {
            throw new IllegalArgumentException("A device named '" + existing.getName()
                    + "' already exists and is not controller " + uid
                    + ". Adopt it under a different name.");
        }
    }

    private String deviceName(String uid, InferrixAdoptRequest request,
                              InferrixControllerClient.InferrixControllerInfo info) {
        if (request.name() != null && !request.name().isBlank()) {
            return request.name().trim();
        }
        return info.name().filter(n -> !n.isBlank()).orElse("Controller " + uid);
    }

    private DeviceProfile findOrCreateProfile(TenantId tenantId) {
        DeviceProfile existing = deviceProfileService.findDeviceProfileByName(tenantId, PROFILE_NAME);
        if (existing != null) {
            return existing;
        }
        MqttDeviceProfileTransportConfiguration transport = new MqttDeviceProfileTransportConfiguration();
        transport.setInferrixTopicRoot(brokerSetting("topicRoot", "com/inferrix"));

        DeviceProfileData data = new DeviceProfileData();
        data.setConfiguration(new DefaultDeviceProfileConfiguration());
        data.setTransportConfiguration(transport);
        data.setProvisionConfiguration(new DisabledDeviceProfileProvisionConfiguration(null));

        DeviceProfile profile = new DeviceProfile();
        profile.setTenantId(tenantId);
        profile.setName(PROFILE_NAME);
        profile.setType(DeviceProfileType.DEFAULT);
        profile.setTransportType(DeviceTransportType.MQTT);
        profile.setDescription("Inferrix soft-PLC controllers. The topic root switches the MQTT "
                + "transport onto the controller's own scheme; clearing it disables that handling.");
        profile.setProfileData(data);
        return deviceProfileService.saveDeviceProfile(profile);
    }

    private void configureBroker(String host, String fingerprint, String token, String uid,
                                 String devicePassword) throws IOException {
        String brokerHost = brokerSetting("host", null);
        if (brokerHost == null || brokerHost.isBlank()) {
            throw new IllegalStateException("No broker host is configured. Set it in the '"
                    + BROKER_SETTINGS_KEY + "' admin settings before adopting a controller.");
        }
        ObjectNode config = JacksonUtil.newObjectNode();
        config.put("enabled", true);
        config.put("host", brokerHost);
        config.put("port", Integer.parseInt(brokerSetting("port", "8883")));
        config.put("username", uid);
        config.put("password", devicePassword);
        config.put("client_id", "infx-" + uid);
        config.put("base_topic", brokerSetting("topicRoot", "com/inferrix"));
        config.put("tls_on", Boolean.parseBoolean(brokerSetting("tls", "true")));
        String caCert = brokerSetting("caCert", null);
        if (caCert != null && !caCert.isBlank()) {
            config.put("ca_cert", caCert);
        }
        client.putMqttConfig(host, fingerprint, token, config);
    }

    private String brokerSetting(String field, String fallback) {
        AdminSettings settings = adminSettingsService.findAdminSettingsByKey(TenantId.SYS_TENANT_ID, BROKER_SETTINGS_KEY);
        if (settings == null || settings.getJsonValue() == null) {
            return fallback;
        }
        String value = settings.getJsonValue().path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private void saveControllerAttributes(TenantId tenantId, Device device, String uid, String host,
                                          String fingerprint, String password, String token) {
        List<AttributeKvEntry> entries = List.of(
                attribute(ATTR_UID, uid),
                attribute(ATTR_IP, host),
                attribute(ATTR_CERT_FINGERPRINT, fingerprint),
                attribute(ATTR_PASSWORD, secretCodec.encrypt(password)),
                attribute(ATTR_TOKEN, secretCodec.encrypt(token)));
        // SERVER_SCOPE deliberately: the controller must never be able to read or overwrite the
        // platform's own record of how to reach and authenticate to it. Everything the device
        // asserts about itself lands in CLIENT_SCOPE via the MQTT identity topic instead.
        // Waited on rather than fired and forgotten: if the sealed password fails to persist, the
        // controller becomes unmanageable the moment its token is rotated, and the operator has to
        // learn that now rather than weeks later.
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        tsSubService.saveAttributes(AttributesSaveRequest.builder()
                .tenantId(tenantId)
                .entityId(device.getId())
                .scope(AttributeScope.SERVER_SCOPE)
                .entries(entries)
                .callback(new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        done.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        failure.set(t);
                        done.countDown();
                    }
                })
                .build());
        try {
            if (!done.await(ATTRIBUTE_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out storing the controller credentials");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while storing the controller credentials", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Failed to store the controller credentials", failure.get());
        }
    }

    private String deviceCredentialsPassword(Device device) {
        DeviceCredentials credentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(
                device.getTenantId(), device.getId());
        BasicMqttCredentials basic = JacksonUtil.fromString(
                credentials.getCredentialsValue(), BasicMqttCredentials.class);
        return basic != null ? basic.getPassword() : null;
    }

    private static AttributeKvEntry attribute(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    private String newPassword() {
        byte[] raw = new byte[GENERATED_PASSWORD_BYTES];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Adoption input. Either a {@code uid} seen announcing, or an explicit {@code host}. */
    public record InferrixAdoptRequest(String uid, String host, String password,
                                       String name, String location, String label) {
    }

}
