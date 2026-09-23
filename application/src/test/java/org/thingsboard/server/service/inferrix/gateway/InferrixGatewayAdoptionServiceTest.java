// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.service.entitiy.device.TbDeviceService;
import org.thingsboard.server.service.inferrix.InferrixSecretCodec;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayAdoption.AdoptRequest;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayToken;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import com.google.common.util.concurrent.Futures;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.dao.attributes.AttributesService;

import java.io.IOException;
import java.util.Collection;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adoption's ordering and its effect on the device, which the pure rules in
 * {@link InferrixGatewayAdoptionTest} cannot reach.
 *
 * <p>Everything here is about a half-adopted gateway: one that exists as a device but cannot be
 * called, or one that was created before the platform had established it could be trusted. Those
 * are worse than a failed adoption, because they look configured.
 */
class InferrixGatewayAdoptionServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String LEAKED =
            "a068bddd974ee22244dde0ceb8b6e521d570fbbc9cff58b4b61dba3ea2578d0b";
    private static final String GOOD_FINGERPRINT = "ab".repeat(32);

    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final User user = new User();
    private final DeviceProfileId profileId = new DeviceProfileId(UUID.randomUUID());

    private InferrixGatewayClient client;
    private InferrixGatewayAccess access;
    private DeviceService deviceService;
    private DeviceProfileService deviceProfileService;
    private TbDeviceService tbDeviceService;
    private TelemetrySubscriptionService tsSubService;
    private AttributesService adoptionAttributes;
    private InferrixGatewaySchemaService schemaService;
    private InferrixGatewayAdoptionService service;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(InferrixGatewayClient.class);
        access = mock(InferrixGatewayAccess.class);
        deviceService = mock(DeviceService.class);
        deviceProfileService = mock(DeviceProfileService.class);
        tbDeviceService = mock(TbDeviceService.class);
        tsSubService = mock(TelemetrySubscriptionService.class);
        adoptionAttributes = mock(AttributesService.class);
        schemaService = mock(InferrixGatewaySchemaService.class);
        when(adoptionAttributes.find(any(), any(), any(AttributeScope.class), anyString()))
                .thenReturn(Futures.immediateFuture(java.util.Optional.empty()));
        when(deviceService.findDevicesByTenantIdAndType(any(), anyString(), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(), 0, 0, false));

        DeviceProfile profile = new DeviceProfile(profileId);
        profile.setName(InferrixGatewayAdoptionService.PROFILE_NAME);
        when(deviceProfileService.findDeviceProfileByName(any(), anyString())).thenReturn(profile);

        when(client.captureFingerprint(anyString())).thenReturn(GOOD_FINGERPRINT);
        when(client.exchangeToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayToken("jwt", 1800));
        when(client.call(anyString(), anyString(), eq("GET"),
                eq(InferrixGatewayAdoption.ADMIN_PROBE_PATH), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(403, "{}"));
        when(tbDeviceService.save(any(), any(), any(User.class))).thenAnswer(invocation -> {
            Device saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(new DeviceId(UUID.randomUUID()));
            }
            return saved;
        });
        // The real service waits on this callback, so a mock that never fires it would hang the
        // test rather than fail it.
        doAnswer(invocation -> {
            invocation.getArgument(0, AttributesSaveRequest.class).getCallback().onSuccess(null);
            return null;
        }).when(tsSubService).saveAttributes(any(AttributesSaveRequest.class));

        service = new InferrixGatewayAdoptionService(client, access, schemaService, new InferrixSecretCodec(KEY),
                deviceService, deviceProfileService, adoptionAttributes, tbDeviceService, tsSubService);
    }

    // --- Nothing is created until the gateway has earned it ----------------------------------

    @Test
    void aGatewayServingTheLeakedCertificateIsRefusedBeforeTheCredentialIsSpent() throws Exception {
        when(client.captureFingerprint(anyString())).thenReturn(LEAKED);

        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("5.1.0");

        // The ordering is the point. Whatever is answering on that address has already proven it
        // holds a private key that is public, so the operator's API token must not be sent to it.
        verify(client, never()).exchangeToken(anyString(), anyString(), anyString(), anyString());
        verify(tbDeviceService, never()).save(any(), any(), any(User.class));
    }

    @Test
    void aTokenTheGatewayRefusesCreatesNoDevice() throws Exception {
        when(client.exchangeToken(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IOException("The gateway refused the API token: HTTP 401"));

        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IOException.class);

        // A mistyped secret must not leave a gateway that looks adopted and answers nothing.
        verify(tbDeviceService, never()).save(any(), any(), any(User.class));
        verify(tsSubService, never()).saveAttributes(any());
    }

    @Test
    void aGatewayThatAnswersTheProbeWithNeitherYesNorNoCreatesNoDevice() throws Exception {
        when(client.call(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(500, "boom"));

        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IllegalStateException.class);
        verify(tbDeviceService, never()).save(any(), any(), any(User.class));
    }

    @Test
    void anAttributeSaveThatFailsFailsTheAdoption() {
        doAnswer(invocation -> {
            invocation.getArgument(0, AttributesSaveRequest.class).getCallback()
                    .onFailure(new RuntimeException("cassandra is down"));
            return null;
        }).when(tsSubService).saveAttributes(any(AttributesSaveRequest.class));

        // The device does get created first, which cannot be avoided -- the attributes hang off
        // its id. What must not happen is reporting success over a gateway whose credential was
        // never stored, because it would be unreachable the moment its JWT expired.
        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been adopted");
    }

    // --- What gets written --------------------------------------------------------------------

    @Test
    void theClientSecretIsSealedAndTheProbeResultIsRecorded() throws Exception {
        service.adopt(tenantId, user, request());

        ArgumentCaptor<AttributesSaveRequest> saved = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        verify(tsSubService).saveAttributes(saved.capture());
        List<AttributeKvEntry> entries = saved.getValue().getEntries();

        String storedSecret = value(entries, InferrixGatewayAccess.CLIENT_SECRET);
        assertThat(storedSecret).isNotEqualTo("s3cr3t");
        assertThat(storedSecret).doesNotContain("s3cr3t");
        assertThat(new InferrixSecretCodec(KEY).decrypt(storedSecret)).isEqualTo("s3cr3t");

        // Sealed, not stored as typed. Two reasons, and the first is not security: the access
        // layer opens it through the codec, so a plaintext id is unopenable and the gateway dies
        // on its first proxied call. The second is that SERVER_SCOPE is not private -- TB's
        // generic attribute read (GET /api/plugins/telemetry/DEVICE/{id}/values/attributes/
        // SERVER_SCOPE) serves it to any caller with READ_ATTRIBUTES, so an id stored as typed is
        // half the credential handed out.
        assertThat(value(entries, InferrixGatewayAccess.CLIENT_ID)).isNotEqualTo("cid");
        assertThat(new InferrixSecretCodec(KEY).decrypt(value(entries, InferrixGatewayAccess.CLIENT_ID)))
                .isEqualTo("cid");
        assertThat(value(entries, InferrixGatewayAccess.CERT_FINGERPRINT)).isEqualTo(GOOD_FINGERPRINT);
        assertThat(value(entries, InferrixGatewayAccess.MANAGEMENT_ADDRESS)).isEqualTo("gw.local");
        assertThat(value(entries, InferrixGatewayAccess.MANAGEMENT_PORT)).isEqualTo("8443");
        // 403 on the probe -- a non-admin service account, which is the expected shape until stack
        // ask A10 lands. Recorded rather than treated as a failure, so the UI can grey that tab.
        assertThat(value(entries, InferrixGatewayAdoptionService.ATTR_PLATFORM_LINK_ADMIN))
                .isEqualTo("false");

        assertThat(saved.getValue().getScope())
                .isEqualTo(org.thingsboard.server.common.data.AttributeScope.SERVER_SCOPE);
        assertThat(saved.getValue().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void theAdoptedDeviceCarriesTheGatewayProfileName() throws Exception {
        Device adopted = service.adopt(tenantId, user, request());

        // The proxy endpoint refuses any device whose type is not this, which is what stops a
        // tenant admin from hand-writing gwManagementAddress onto an arbitrary device and using
        // the proxy to reach private hosts from the platform's network position. That guard is
        // only as good as this assignment, so the seam is pinned on both sides.
        assertThat(adopted.getType()).isEqualTo(InferrixGatewayAdoptionService.PROFILE_NAME);
        assertThat(adopted.getDeviceProfileId()).isEqualTo(profileId);
    }

    @Test
    void theDefaultPortIsUsedWhenTheOperatorLeavesItBlank() throws Exception {
        service.adopt(tenantId, user,
                new AdoptRequest("gw-1", "gw.local", null, "cid", "s3cr3t", null));

        verify(client).captureFingerprint("https://gw.local:443");
    }

    // --- Re-adoption ---------------------------------------------------------------------------

    @Test
    void readoptingAGatewayUpdatesTheDeviceItAlreadyHas() throws Exception {
        Device existing = new Device(new DeviceId(UUID.randomUUID()));
        existing.setTenantId(tenantId);
        existing.setName("gw-1");
        existing.setDeviceProfileId(profileId);
        when(deviceService.findDeviceByTenantIdAndName(tenantId, "gw-1")).thenReturn(existing);

        Device adopted = service.adopt(tenantId, user, request());

        // Same device, not a second one beside it. Re-adoption is how an operator repoints a
        // gateway at replacement hardware or a reissued token, and it has to be safe to repeat.
        assertThat(adopted.getId()).isEqualTo(existing.getId());
        verify(access).forget(existing.getId());
        // Replacement hardware may run a different stack version, whose model types are not the
        // ones the cached schema document describes.
        verify(schemaService).forget(existing.getId());
    }

    @Test
    void readoptingOntoADeviceThatIsNotAGatewayIsRefused() throws Exception {
        Device unrelated = new Device(new DeviceId(UUID.randomUUID()));
        unrelated.setTenantId(tenantId);
        unrelated.setName("gw-1");
        unrelated.setDeviceProfileId(new DeviceProfileId(UUID.randomUUID()));
        when(deviceService.findDeviceByTenantIdAndName(tenantId, "gw-1")).thenReturn(unrelated);

        // Otherwise adoption would move someone's live device onto the gateway profile and seal a
        // credential onto it, breaking whatever was connecting as it -- with no way back.
        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different name");
        verify(tbDeviceService, never()).save(any(), any(), any(User.class));
    }

    @Test
    void adoptionIsLookedUpWithinTheAdoptingTenant() throws Exception {
        service.adopt(tenantId, user, request());

        // Device name collisions are per-tenant, so the lookup has to be too -- otherwise a name
        // taken in another tenant would either block this adoption or, worse, find that tenant's
        // device. findDeviceByTenantIdAndName is a derived query with a real tenant predicate,
        // unlike findById, which discards the argument.
        verify(deviceService).findDeviceByTenantIdAndName(tenantId, "gw-1");
        ArgumentCaptor<Device> written = ArgumentCaptor.forClass(Device.class);
        verify(tbDeviceService).save(written.capture(), any(), any(User.class));
        assertThat(written.getValue().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void whatAdoptionWritesIsWhatTheAccessLayerCanRead() throws Exception {
        // The one test that joins the writer to the reader. Without it each side is free to stub
        // its own convention and both suites stay green while the feature is entirely broken --
        // which is exactly what happened: adoption stored the client id as typed, the access layer
        // opened it through the codec, and every adopted gateway 500'd on its first proxied call
        // with a message blaming the sealing key. Re-adoption rewrote the same plaintext, so there
        // was no supported way back.
        service.adopt(tenantId, user, request());

        ArgumentCaptor<AttributesSaveRequest> saved = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        verify(tsSubService).saveAttributes(saved.capture());

        AttributesService attributesService = mock(AttributesService.class);
        when(attributesService.find(any(), any(), eq(AttributeScope.SERVER_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(saved.getValue().getEntries()));
        when(attributesService.find(any(), any(), eq(AttributeScope.CLIENT_SCOPE), any(Collection.class)))
                .thenReturn(Futures.immediateFuture(List.of()));

        InferrixGatewayClient callClient = mock(InferrixGatewayClient.class);
        when(callClient.exchangeToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GatewayToken("jwt", 1800));
        when(callClient.call(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new GatewayResponse(200, "{}"));

        new InferrixGatewayAccess(callClient, new InferrixSecretCodec(KEY), attributesService)
                .call(tenantId, new DeviceId(UUID.randomUUID()), "GET", "/v2/about", null, null);

        // The credentials that reach the wire are the ones the operator typed, recovered through
        // the seal -- and the address and pin adoption recorded.
        verify(callClient).exchangeToken("https://gw.local:8443", GOOD_FINGERPRINT, "cid", "s3cr3t");
    }

    // --- Re-adoption must not silently repoint a device at different plant --------------------

    @Test
    void readoptingAgainstADifferentGatewayIsRefusedUnlessConfirmed() throws Exception {
        Device existing = existingGateway();
        stubStoredFingerprint("cd".repeat(32));

        // Pure operator error, no attacker: a tenant has "gw-1" at 10.0.0.5, and someone adopts a
        // NEW gateway reusing the name from a saved form or a copy-pasted runbook. Without this
        // the address, pin and credential are all overwritten and 200 returned -- and every
        // dashboard, alarm rule and telemetry series on that device now reflects different
        // physical plant, with nothing in the record saying which gateway it is.
        //
        // /v2/about carries no stable instance id (hostName is getLocalHost().getHostName() and
        // startTime resets on restart), so the certificate is the identity: since 5.1.0 each
        // gateway generates its own key pair at first boot.
        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different certificate");
        verify(tbDeviceService, never()).save(any(), any(), any(User.class));
        // And refused before the credential is spent, for the same reason the leaked-certificate
        // check is: this may not be the gateway the operator thinks it is.
        verify(client, never()).exchangeToken(anyString(), anyString(), anyString(), anyString());

        // Replacing failed hardware under the same name is legitimate and must stay possible --
        // the dashboards point at this device. It just has to be said out loud.
        Device adopted = service.adopt(tenantId, user,
                new AdoptRequest("gw-1", "gw.local", 8443, "cid", "s3cr3t", true));
        assertThat(adopted.getId()).isEqualTo(existing.getId());
    }

    @Test
    void readoptingTheSameGatewayNeedsNoConfirmation() throws Exception {
        existingGateway();
        stubStoredFingerprint(GOOD_FINGERPRINT);

        // Rotating a token, or correcting a port, is routine. Demanding a confirmation flag for it
        // would train operators to always pass it, which would cost the check its meaning.
        service.adopt(tenantId, user, request());
        verify(tbDeviceService).save(any(), any(), any(User.class));
    }

    @Test
    void aFirstAdoptionNeedsNoConfirmationEither() throws Exception {
        // Nothing stored, nothing to contradict.
        service.adopt(tenantId, user, request());
        verify(tbDeviceService).save(any(), any(), any(User.class));
    }

    @Test
    void aFailedSaveOnlySuggestsRemovingADeviceAdoptionItselfCreated() throws Exception {
        doAnswer(invocation -> {
            invocation.getArgument(0, AttributesSaveRequest.class).getCallback()
                    .onFailure(new RuntimeException("cassandra is down"));
            return null;
        }).when(tsSubService).saveAttributes(any(AttributesSaveRequest.class));

        // On a first adoption the device really is orphaned, so removing it is right.
        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .hasMessageContaining("Remove the device");

        // On a re-adoption it is not. That device may carry years of telemetry, dashboards and
        // alarm rules, and its previous credentials still work -- telling the operator to delete
        // it is unrecoverable advice for a transient database failure.
        existingGateway();
        stubStoredFingerprint(GOOD_FINGERPRINT);
        assertThatThrownBy(() -> service.adopt(tenantId, user, request()))
                .hasMessageContaining("previous configuration is still in place")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Remove the device"));
    }

    // --- The pending list ----------------------------------------------------------------------

    @Test
    void pendingListsGatewaysThatHaveProvisionedThemselvesButAreNotAdopted() throws Exception {
        Device unadopted = gatewayDevice("gw-new");
        Device adopted = gatewayDevice("gw-old");
        when(deviceService.findDevicesByTenantIdAndType(eq(tenantId),
                eq(InferrixGatewayAdoptionService.PROFILE_NAME), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(unadopted, adopted), 1, 2, false));

        // An adopted gateway is one the platform has sealed an address onto. Nothing else
        // distinguishes the two -- both self-provisioned over MQTT and both sit on this profile.
        when(adoptionAttributes.find(any(), eq(adopted.getId()), eq(AttributeScope.SERVER_SCOPE),
                eq(InferrixGatewayAccess.MANAGEMENT_ADDRESS)))
                .thenReturn(Futures.immediateFuture(java.util.Optional.of(
                        strAttr(InferrixGatewayAccess.MANAGEMENT_ADDRESS, "10.0.0.9"))));
        // The unadopted one reports where it can be reached, over MQTT, under its own key (stack
        // ask A3) -- so the adopt form can prefill rather than making the operator find it.
        when(adoptionAttributes.find(any(), eq(unadopted.getId()), eq(AttributeScope.CLIENT_SCOPE),
                eq(InferrixGatewayAccess.REPORTED_ADDRESS)))
                .thenReturn(Futures.immediateFuture(java.util.Optional.of(
                        strAttr(InferrixGatewayAccess.REPORTED_ADDRESS, "10.0.0.5"))));

        List<InferrixGatewayAdoptionService.PendingGateway> pending = service.pending(tenantId);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).deviceId()).isEqualTo(unadopted.getId());
        assertThat(pending.get(0).name()).isEqualTo("gw-new");
        assertThat(pending.get(0).reportedAddress()).isEqualTo("10.0.0.5");
    }

    @Test
    void aPendingGatewayThatHasNotReportedAnAddressIsStillListed() throws Exception {
        Device silent = gatewayDevice("gw-silent");
        when(deviceService.findDevicesByTenantIdAndType(any(), anyString(), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(silent), 1, 1, false));

        // Dropping it would hide a gateway the operator can see in their own network and adopt by
        // hand. An absent address is a blank field on the form, not a reason to omit the row.
        List<InferrixGatewayAdoptionService.PendingGateway> pending = service.pending(tenantId);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).reportedAddress()).isNull();
    }

    @Test
    void pendingIsScopedToTheAskingTenantAndBounded() throws Exception {
        when(deviceService.findDevicesByTenantIdAndType(any(), anyString(), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(), 0, 0, false));

        service.pending(tenantId);

        ArgumentCaptor<PageLink> page = ArgumentCaptor.forClass(PageLink.class);
        verify(deviceService).findDevicesByTenantIdAndType(
                eq(tenantId), eq(InferrixGatewayAdoptionService.PROFILE_NAME), page.capture());
        // A tenant with thousands of gateways must not turn this page into an unbounded scan of
        // every device plus an attribute read each.
        assertThat(page.getValue().getPageSize()).isLessThanOrEqualTo(1000);
    }

    private Device gatewayDevice(String name) {
        Device device = new Device(new DeviceId(UUID.randomUUID()));
        device.setTenantId(tenantId);
        device.setName(name);
        device.setType(InferrixGatewayAdoptionService.PROFILE_NAME);
        device.setDeviceProfileId(profileId);
        return device;
    }

    private static AttributeKvEntry strAttr(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    private Device existingGateway() {
        Device existing = new Device(new DeviceId(UUID.randomUUID()));
        existing.setTenantId(tenantId);
        existing.setName("gw-1");
        existing.setDeviceProfileId(profileId);
        when(deviceService.findDeviceByTenantIdAndName(tenantId, "gw-1")).thenReturn(existing);
        return existing;
    }

    private void stubStoredFingerprint(String fingerprint) {
        when(adoptionAttributes.find(any(), any(), eq(AttributeScope.SERVER_SCOPE),
                eq(InferrixGatewayAccess.CERT_FINGERPRINT)))
                .thenReturn(Futures.immediateFuture(java.util.Optional.of(
                        new BaseAttributeKvEntry(new StringDataEntry(
                                InferrixGatewayAccess.CERT_FINGERPRINT, fingerprint),
                                System.currentTimeMillis()))));
    }

    private static AdoptRequest request() {
        return new AdoptRequest("gw-1", "gw.local", 8443, "cid", "s3cr3t", null);
    }

    private static String value(List<AttributeKvEntry> entries, String key) {
        return entries.stream()
                .filter(e -> e.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no attribute " + key))
                .getValueAsString();
    }
}
