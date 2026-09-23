// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.SettableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.device.TbDeviceService;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoption.AdoptRequest;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoption.ConnectionRequest;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayToken;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Brings a gateway under platform management.
 *
 * <p>Every judgement this makes lives in {@link InferrixGatewayAdoption}, which is why there is so
 * little branching here: this class is the machinery — the device, the profile, the attribute write
 * — and defers what to allow.
 *
 * <p><b>Order matters and is not incidental.</b> The certificate is captured and vetted before the
 * credential is spent, the credential is proven against the gateway before anything is written, and
 * the device is written before the credential is sealed onto it. A gateway that half-adopts is worse
 * than one that fails to adopt, because it looks configured.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixGatewayAdoptionService {

    public static final String PROFILE_NAME = "Inferrix Gateway";

    /**
     * Whether the API token reaches the platform-link domain, settled once at adoption.
     *
     * <p>The UI needs this to decide whether to offer that tab at all, and asking on every page
     * load would spend a call on a question whose answer only changes when the operator reissues
     * the token — at which point they re-adopt. Recorded, not inferred: see
     * {@link InferrixGatewayAdoption#isAdminFrom(int)}.
     */
    public static final String ATTR_PLATFORM_LINK_ADMIN = "gwPlatformLinkAdmin";

    private static final int ATTRIBUTE_SAVE_TIMEOUT_SECONDS = 30;

    /** Bounds the pending scan, so a tenant with thousands of gateways cannot turn it into a sweep. */
    private static final int MAX_PENDING_SCAN = 1000;

    private final InferrixGatewayClient client;
    private final InferrixGatewayAccess gatewayAccess;
    private final InferrixGatewaySchemaService schemaService;
    private final InferrixSecretCodec secretCodec;
    private final DeviceService deviceService;
    private final DeviceProfileService deviceProfileService;
    private final AttributesService attributesService;
    private final TbDeviceService tbDeviceService;
    private final TelemetrySubscriptionService tsSubService;

    /**
     * Gateways that have provisioned themselves but that the platform has not adopted.
     *
     * <p>"Adopted" means exactly one thing here: the platform has sealed a management address onto
     * the device. Nothing else separates the two — both self-provisioned over MQTT and both sit on
     * this profile — so that attribute is the whole test.
     *
     * <p>ponytail: one scan capped at {@link #MAX_PENDING_SCAN} devices, then one attribute read
     * each. A tenant with more gateways than that sees the first page worth; if that ever becomes
     * real, the filter belongs in a query rather than here. Deliberately not paged: filtering after
     * paging would make the page counts describe the profile rather than the pending set, which is
     * a table that lies about how much is left.
     */
    public List<PendingGateway> pending(TenantId tenantId) throws Exception {
        PageData<Device> devices = deviceService.findDevicesByTenantIdAndType(
                tenantId, PROFILE_NAME, new PageLink(MAX_PENDING_SCAN));

        List<PendingGateway> pending = new ArrayList<>();
        for (Device device : devices.getData()) {
            if (attribute(tenantId, device.getId(), AttributeScope.SERVER_SCOPE,
                    InferrixGatewayAccess.MANAGEMENT_ADDRESS) != null) {
                continue;
            }
            // Read only for the ones that turned out pending. The reported address is the
            // gateway's own MQTT-published claim (stack ask A3), used to prefill the adopt form so
            // the operator does not have to go and find it — never to reach the device, which is
            // why it stays in CLIENT_SCOPE where the device can write it.
            String reported = attribute(tenantId, device.getId(), AttributeScope.CLIENT_SCOPE,
                    InferrixGatewayAccess.REPORTED_ADDRESS);
            pending.add(new PendingGateway(device.getId(), device.getName(), reported,
                    device.getCreatedTime()));
        }
        return pending;
    }

    private String attribute(TenantId tenantId, DeviceId deviceId, AttributeScope scope, String key)
            throws Exception {
        return attributesService.find(tenantId, deviceId, scope, key).get()
                .flatMap(AttributeKvEntry::getStrValue)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    public Device adopt(TenantId tenantId, User user, AdoptRequest request) throws Exception {
        InferrixGatewayAdoption.requireSealingKey(secretCodec);
        InferrixGatewayAdoption.validate(request);

        int port = InferrixGatewayAdoption.portOrDefault(request.port());
        String baseUrl = "https://" + request.address() + ":" + port;

        // Trust on first use, with one exception that is the whole point of doing it here: a
        // gateway still serving the pre-5.1.0 keystore is presenting a certificate whose private
        // key is public, and pinning it would record a fingerprint that authenticates nobody.
        String fingerprint = client.captureFingerprint(baseUrl);
        requireUsableCertificate(fingerprint, request.address());

        // Spending the credential is also how it is validated: a gateway that refuses the token
        // throws here, before any device exists, rather than leaving one that cannot be reached.
        // Before the credential is spent, and for the same reason the certificate is vetted
        // first: establish that this is the gateway the operator means. See requireSameGateway.
        Device existing = deviceService.findDeviceByTenantIdAndName(tenantId, deviceName(request));
        boolean created = existing == null;
        requireSameGateway(tenantId, existing, fingerprint, request.address(),
                request.confirmsDifferentGateway());

        GatewayToken token = client.exchangeToken(
                baseUrl, fingerprint, request.clientId(), request.clientSecret());
        GatewayResponse probe = client.call(baseUrl, fingerprint, "GET",
                InferrixGatewayAdoption.ADMIN_PROBE_PATH, null, token.accessToken(), null);
        boolean platformLinkAdmin = InferrixGatewayAdoption.isAdminFrom(probe.statusCode());

        Device device = registerDevice(tenantId, user, request, existing);
        saveGatewayAttributes(tenantId, device.getId(), request, port, fingerprint,
                platformLinkAdmin, created);
        // A re-adoption may have changed the credential under a JWT this node still holds, and it
        // may be replacement hardware on a different stack version -- whose model types are not
        // the ones the cached schema document describes.
        gatewayAccess.forget(device.getId());
        schemaService.forget(device.getId());

        log.info("Adopted Inferrix gateway at {}:{} as device {} (platform-link admin: {})",
                request.address(), port, device.getId(), platformLinkAdmin);
        return device;
    }

    /**
     * Moves an adopted gateway to a new address, spending its own sealed credential to prove it.
     *
     * <p>This exists because the address is a property of the network, not of the box. A gateway is
     * reachable on a LAN or a VPN and nowhere else, so a renumbered subnet, a new VPN or a moved
     * cabinet changes where it lives while the hardware, its certificate and its API token stay
     * exactly as they were. Re-adoption could already express that, but only by asking for a client
     * secret Cortex sealed and never gives back — so in practice the answer was to write the
     * attributes by hand, which skips both checks below.
     *
     * <p>Nothing is written until the new address answers, and the order is adoption's for
     * adoption's reasons: capture the certificate, judge it, then spend the credential. A typo that
     * lands on another host fails at the certificate; a host that is not this gateway fails at the
     * token; and either way the device keeps the configuration that was working.
     *
     * <p>What it deliberately does not touch: the credential, which has not changed, and
     * {@link #ATTR_PLATFORM_LINK_ADMIN}, which describes the account that token is bound to rather
     * than the address it is spent at.
     */
    public void changeConnection(TenantId tenantId, Device device, ConnectionRequest request)
            throws Exception {
        InferrixGatewayAdoption.requireSealingKey(secretCodec);
        if (request == null) {
            throw new IllegalArgumentException("No connection request was supplied");
        }
        InferrixGatewayAdoption.validateAddress(request.address(), request.port());

        DeviceId deviceId = device.getId();
        String clientId = attribute(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                InferrixGatewayAccess.CLIENT_ID);
        String clientSecret = attribute(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                InferrixGatewayAccess.CLIENT_SECRET);
        // A pinned certificate is required, not merely compared against. Adoption can trust on
        // first use because the operator is typing the credential as they do it; here the platform
        // would be posting a sealed secret it holds to whatever answers, on the say-so of an
        // address alone. A gateway with no pin cannot be called at all -- InferrixGatewayAccess
        // refuses it -- so this rules out nothing that was working.
        String pinned = attribute(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                InferrixGatewayAccess.CERT_FINGERPRINT);
        if (clientId == null || clientSecret == null || pinned == null) {
            throw new IllegalArgumentException("'" + device.getName() + "' has no sealed API token"
                    + " and pinned certificate, so there is nothing to prove a new address with."
                    + " Adopt it instead.");
        }

        int port = InferrixGatewayAdoption.portOrDefault(request.port());
        String baseUrl = "https://" + request.address() + ":" + port;

        String fingerprint = client.captureFingerprint(baseUrl);
        requireUsableCertificate(fingerprint, request.address());
        requireSameGateway(tenantId, device, fingerprint, request.address(),
                request.confirmsDifferentGateway());
        // Proves two things at once, and both matter: that something at the new address speaks the
        // gateway API, and that it accepts this device's credential. Without it a successful save
        // would mean only that a TLS handshake completed.
        client.exchangeToken(baseUrl, fingerprint,
                secretCodec.decrypt(clientId), secretCodec.decrypt(clientSecret));

        long now = System.currentTimeMillis();
        saveServerAttributes(tenantId, deviceId, List.of(
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.MANAGEMENT_ADDRESS, request.address()), now),
                new BaseAttributeKvEntry(new LongDataEntry(
                        InferrixGatewayAccess.MANAGEMENT_PORT, (long) port), now),
                // Re-pinned, not left alone. Replacement hardware at the new address presents its
                // own certificate, and the operator has just confirmed that above; keeping the old
                // pin would make every call afterwards fail as a changed certificate.
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.CERT_FINGERPRINT, fingerprint), now)), false);

        // The cached JWT was issued by the gateway at the old address and the cached schema
        // document describes whatever was there; neither survives the move as a safe assumption.
        gatewayAccess.forget(deviceId);
        schemaService.forget(deviceId);

        log.info("Inferrix gateway {} now reached at {}:{}", deviceId, request.address(), port);
    }

    /**
     * Trust on first use, with the one exception that is the whole point of doing it here.
     *
     * <p>A gateway still serving the pre-5.1.0 keystore presents a certificate whose private key is
     * public, so pinning it would record a fingerprint that authenticates nobody.
     */
    private void requireUsableCertificate(String fingerprint, String address) {
        if (InferrixGatewayAdoption.isKnownBadCertificate(fingerprint)) {
            throw new IllegalStateException("The gateway at " + address + " is serving the"
                    + " default certificate that shipped with every Inferrix stack before 5.1.0."
                    + " Its private key is public, so pinning it would secure nothing. Upgrade the"
                    + " gateway to 5.1.0 or later, which generates its own key pair, and try again.");
        }
    }

    /**
     * Refuses to repoint an existing gateway device at what is provably a different gateway.
     *
     * <p>The sibling controller service anchors this on the device's reported {@code uid}. A
     * gateway has no equivalent — {@code /v2/about} carries a host name from
     * {@code InetAddress.getLocalHost()}, which is neither unique nor stable, and a start time that
     * resets on restart. The certificate is the identity instead: since stack 5.1.0 each gateway
     * generates its own key pair on first boot, so a changed fingerprint means different hardware.
     *
     * <p>Not a hard refusal, because replacing a failed gateway under the same name is legitimate
     * and the dashboards point at that device. The operator just has to say so, which is the whole
     * difference between a deliberate replacement and adopting the wrong box from a saved form.
     */
    private void requireSameGateway(TenantId tenantId, Device existing, String fingerprint,
                                    String address, boolean confirmed) throws Exception {
        if (existing == null || confirmed) {
            return;
        }
        Optional<AttributeKvEntry> stored = attributesService.find(
                tenantId, existing.getId(), AttributeScope.SERVER_SCOPE,
                InferrixGatewayAccess.CERT_FINGERPRINT).get();
        String previous = stored.flatMap(AttributeKvEntry::getStrValue).orElse(null);
        if (previous != null && !previous.equalsIgnoreCase(fingerprint)) {
            throw new IllegalArgumentException("'" + existing.getName() + "' is already adopted and"
                    + " the gateway answering at " + address + " presents a different"
                    + " certificate, so it is different hardware. Everything recorded against this"
                    + " device — dashboards, alarm rules, telemetry — refers to the old gateway."
                    + " If you are replacing it, confirm that and adopt again; otherwise adopt the"
                    + " new gateway under its own name.");
        }
    }

    private Device registerDevice(TenantId tenantId, User user, AdoptRequest request,
                                  Device existing) throws Exception {
        DeviceProfile profile = findOrCreateProfile(tenantId);
        String name = deviceName(request);
        Device device = existing;
        if (device == null) {
            device = new Device();
            device.setTenantId(tenantId);
            device.setName(name);
        } else if (!profile.getId().equals(device.getDeviceProfileId())) {
            // Re-adopting a gateway is fine and idempotent, including onto replacement hardware:
            // the operator typed the address and pasted the token, so a changed certificate is a
            // decision, not a surprise. Landing on some unrelated device that merely shares the
            // name is not fine — this would move it onto the gateway profile and seal a credential
            // onto it, breaking whatever was connecting as it.
            throw new IllegalArgumentException("A device named '" + name + "' already exists and is"
                    + " not an Inferrix gateway. Adopt this gateway under a different name.");
        }
        device.setType(profile.getName());
        device.setDeviceProfileId(profile.getId());
        // Null access token: TbDeviceService generates one. A gateway never uses it — it is reached
        // over REST with its own API token — but TB gives every device credentials, and inventing a
        // value here would only be a second secret to look after.
        return tbDeviceService.save(device, null, user);
    }

    private String deviceName(AdoptRequest request) {
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            return request.deviceName().trim();
        }
        return "Gateway " + request.address();
    }

    private DeviceProfile findOrCreateProfile(TenantId tenantId) {
        DeviceProfile existing = deviceProfileService.findDeviceProfileByName(tenantId, PROFILE_NAME);
        if (existing != null) {
            return existing;
        }
        DeviceProfileData data = new DeviceProfileData();
        data.setConfiguration(new DefaultDeviceProfileConfiguration());
        // DEFAULT transport, unlike the controller's MQTT profile: a gateway is managed entirely
        // over its REST API. It does publish over MQTT (stack ask A3), but as an ordinary client
        // needing no transport-level special handling.
        data.setTransportConfiguration(new DefaultDeviceProfileTransportConfiguration());
        data.setProvisionConfiguration(new DisabledDeviceProfileProvisionConfiguration(null));

        DeviceProfile profile = new DeviceProfile();
        profile.setTenantId(tenantId);
        profile.setName(PROFILE_NAME);
        profile.setType(DeviceProfileType.DEFAULT);
        profile.setTransportType(DeviceTransportType.DEFAULT);
        profile.setDescription("Inferrix gateways. Devices on this profile are configured from the "
                + "Gateways page, which proxies to the gateway's own REST API.");
        profile.setProfileData(data);
        return deviceProfileService.saveDeviceProfile(profile);
    }

    private void saveGatewayAttributes(TenantId tenantId, DeviceId deviceId, AdoptRequest request,
                                       int port, String fingerprint, boolean platformLinkAdmin,
                                       boolean created) throws Exception {
        long now = System.currentTimeMillis();
        List<AttributeKvEntry> entries = List.of(
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.MANAGEMENT_ADDRESS, request.address()), now),
                new BaseAttributeKvEntry(new LongDataEntry(
                        InferrixGatewayAccess.MANAGEMENT_PORT, (long) port), now),
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.CERT_FINGERPRINT, fingerprint), now),
                // Sealed, like the secret. Not because an OAuth2 client id is confidential by
                // convention, but because SERVER_SCOPE is not private: TB's generic attribute read
                // serves it to anyone holding READ_ATTRIBUTES on the device, and an id stored as
                // typed is half the credential given away. InferrixGatewayAccess opens both
                // through the codec, so this is also what makes the gateway callable at all.
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.CLIENT_ID,
                        secretCodec.encrypt(request.clientId())), now),
                new BaseAttributeKvEntry(new StringDataEntry(
                        InferrixGatewayAccess.CLIENT_SECRET,
                        secretCodec.encrypt(request.clientSecret())), now),
                new BaseAttributeKvEntry(new BooleanDataEntry(
                        ATTR_PLATFORM_LINK_ADMIN, platformLinkAdmin), now));

        saveServerAttributes(tenantId, deviceId, entries, created);
    }

    /**
     * SERVER_SCOPE deliberately: the gateway must never be able to read or overwrite the platform's
     * own record of how to reach and authenticate to it. What the device asserts about itself lands
     * in CLIENT_SCOPE under its own key instead.
     *
     * <p>Waited on rather than fired and forgotten — if this fails to persist, the gateway is
     * unreachable from the moment its JWT expires, and the operator needs to learn that now rather
     * than an hour later.
     *
     * @param created whether this is a first adoption, which is the only thing that changes what
     *                the operator should do about a failure
     */
    private void saveServerAttributes(TenantId tenantId, DeviceId deviceId,
                                      List<AttributeKvEntry> entries, boolean created)
            throws Exception {
        SettableFuture<Void> saved = SettableFuture.create();
        tsSubService.saveAttributes(AttributesSaveRequest.builder()
                .tenantId(tenantId)
                .entityId(deviceId)
                .scope(AttributeScope.SERVER_SCOPE)
                .entries(entries)
                .callback(new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        saved.set(null);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        saved.setException(t);
                    }
                })
                .build());
        try {
            saved.get(ATTRIBUTE_SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Deliberately different advice per case. On a first adoption the device really is
            // orphaned and removing it is right. On a re-adoption or a change of address it is
            // not: that device may carry years of telemetry, dashboards and alarm rules, and its
            // previous settings still work — telling the operator to delete it would be
            // unrecoverable advice for what is usually a transient database failure.
            throw new IllegalStateException(created
                    ? "The gateway's credentials could not be stored, so it has not been adopted."
                            + " Remove the device and try again."
                    : "The gateway's settings could not be stored, so nothing was changed and"
                            + " the previous configuration is still in place. Try again.", e);
        }
    }

    /**
     * A gateway waiting to be adopted.
     *
     * @param reportedAddress what the gateway says its management address is, or {@code null} if it
     *                        has not said. Absent is a blank field on the adopt form, never a
     *                        reason to hide the row — the operator can see the device on their own
     *                        network and type it.
     */
    public record PendingGateway(DeviceId deviceId, String name, String reportedAddress,
                                 long createdTime) {
    }
}
