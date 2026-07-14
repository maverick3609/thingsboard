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
package org.thingsboard.server.service.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.OtaPackageInfo;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.OtaPackageId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.ota.OtaPackageType;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.queue.TbCallback;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.dao.scheduler.SchedulerEventService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;
import org.thingsboard.server.service.ota.OtaPackageStateService;
import org.thingsboard.server.service.scheduler.report.SchedulerReportExecutor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultSchedulerServiceTest {

    @Mock private TenantService tenantService;
    @Mock private TbClusterService clusterService;
    @Mock private PartitionService partitionService;
    @Mock private SchedulerEventService schedulerEventService;
    @Mock private OtaPackageStateService otaStateService;
    @Mock private DeviceService deviceService;
    @Mock private DeviceProfileService deviceProfileService;
    @Mock private OtaPackageService otaPackageService;
    @Mock private TbServiceInfoProvider serviceInfoProvider;
    @Mock private SchedulerReportExecutor reportExecutor;

    private DefaultSchedulerService service;
    private final TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    private final SchedulerEventId eventId = new SchedulerEventId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        when(serviceInfoProvider.getServiceId()).thenReturn("tb-core-test");
        service = new DefaultSchedulerService(tenantService, clusterService, partitionService,
                schedulerEventService, otaStateService, deviceService, deviceProfileService,
                otaPackageService, serviceInfoProvider, Optional.of(reportExecutor));
        ReflectionTestUtils.setField(service, "minTimeout", 5000L);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private SchedulerEvent event(String type, String configJson, long startInMs) {
        SchedulerEvent event = new SchedulerEvent(eventId);
        event.setTenantId(tenantId);
        event.setCustomerId(new CustomerId(UUID.randomUUID()));
        event.setName("test-" + type);
        event.setType(type);
        event.setEnabled(true);
        event.setSchedule(JacksonUtil.toJsonNode(
                "{\"timezone\":\"UTC\",\"startTime\":" + (System.currentTimeMillis() + startInMs) + "}"));
        JsonNode configuration = JacksonUtil.toJsonNode(configJson);
        event.setConfiguration(configuration);
        // BaseSchedulerEventService.getOriginatorId reads the SchedulerEventInfo bean property (not the
        // configuration JSON), so mirror the configured originatorId onto the bean, same as the real
        // save path (BaseSchedulerEventService/validators) would populate it from the API request.
        JsonNode originatorNode = configuration.get("originatorId");
        if (originatorNode != null && !originatorNode.isNull()) {
            event.setOriginatorId(JacksonUtil.convertValue(originatorNode, EntityId.class));
        }
        return event;
    }

    private void stubAndDeliver(SchedulerEvent event) {
        when(schedulerEventService.findSchedulerEventInfoById(tenantId, eventId)).thenReturn(event);
        when(schedulerEventService.findSchedulerEventById(tenantId, eventId)).thenReturn(event);
        TransportProtos.SchedulerServiceMsgProto proto = TransportProtos.SchedulerServiceMsgProto.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setEventIdMSB(eventId.getId().getMostSignificantBits())
                .setEventIdLSB(eventId.getId().getLeastSignificantBits())
                .setAdded(true).build();
        service.onQueueMsg(proto, mock(TbCallback.class));
    }

    @Test
    void firesUpdateAttributes_asRuleEngineMsg_withDefaultMetadata() {
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        SchedulerEvent event = event("updateAttributes",
                "{\"originatorId\":{\"entityType\":\"DEVICE\",\"id\":\"" + deviceId.getId() + "\"}," +
                "\"msgType\":null,\"msgBody\":{\"temp\":22},\"metadata\":null}", 150);
        stubAndDeliver(event);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(clusterService, timeout(3000)).pushMsgToRuleEngine(eq(tenantId), eq(deviceId), captor.capture(), isNull());
        TbMsg msg = captor.getValue();
        assertThat(msg.getType()).isEqualTo("updateAttributes");
        assertThat(msg.getOriginator()).isEqualTo(deviceId);
        assertThat(JacksonUtil.toJsonNode(msg.getData()).get("temp").asInt()).isEqualTo(22);
        assertThat(msg.getMetaData().getValue("eventName")).isEqualTo("test-updateAttributes");
        assertThat(msg.getMetaData().getValue("customerId")).isNotNull();
    }

    @Test
    void firesRpc_withExplicitMetadata_expirationAndOriginService() {
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        long before = System.currentTimeMillis();
        SchedulerEvent event = event("sendRpcRequest",
                "{\"originatorId\":{\"entityType\":\"DEVICE\",\"id\":\"" + deviceId.getId() + "\"}," +
                "\"msgType\":null,\"msgBody\":{\"method\":\"reboot\",\"params\":{}}," +
                "\"metadata\":{\"oneway\":\"true\",\"timeout\":\"20000\"}}", 150);
        stubAndDeliver(event);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(clusterService, timeout(3000)).pushMsgToRuleEngine(eq(tenantId), eq(deviceId), captor.capture(), isNull());
        TbMsg msg = captor.getValue();
        assertThat(msg.getMetaData().getValue("originServiceId")).isEqualTo("tb-core-test");
        long expiration = Long.parseLong(msg.getMetaData().getValue("expirationTime"));
        assertThat(expiration).isGreaterThanOrEqualTo(before + 20000);
        assertThat(msg.getMetaData().getValue("oneway")).isEqualTo("true");
    }

    @Test
    void generateDashboardReport_mapsMsgTypeToGenerateReport() {
        SchedulerEvent event = event("generateDashboardReport",
                "{\"originatorId\":null,\"msgType\":null,\"msgBody\":{},\"metadata\":null}", 150);
        stubAndDeliver(event);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(clusterService, timeout(3000)).pushMsgToRuleEngine(eq(tenantId), eq(eventId), captor.capture(), isNull());
        assertThat(captor.getValue().getType()).isEqualTo(TbMsgType.generateReport.name());
        assertThat(captor.getValue().getOriginator()).isEqualTo(eventId); // no originator configured -> event id
    }

    @Test
    void generateReport_invokesSeam_andSkipsRuleEngine() {
        SchedulerEvent event = event("generateReport",
                "{\"originatorId\":null,\"msgType\":null,\"msgBody\":{},\"metadata\":null}", 150);
        stubAndDeliver(event);

        verify(reportExecutor, timeout(3000)).executeReport(eq(tenantId), any(SchedulerEvent.class));
        verify(clusterService, never()).pushMsgToRuleEngine(any(TenantId.class), any(EntityId.class), any(TbMsg.class), any());
    }

    @Test
    void updateFirmware_onDevice_setsFirmware_andStillPushesRuleEngineMsg() {
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        OtaPackageId otaId = new OtaPackageId(UUID.randomUUID());
        OtaPackageInfo ota = new OtaPackageInfo();
        ota.setId(otaId);
        ota.setType(OtaPackageType.FIRMWARE);
        when(otaPackageService.findOtaPackageInfoById(eq(tenantId), eq(otaId))).thenReturn(ota);
        Device device = new Device(deviceId);
        when(deviceService.findDeviceById(eq(tenantId), eq(deviceId))).thenReturn(device);

        SchedulerEvent event = event("updateFirmware",
                "{\"originatorId\":{\"entityType\":\"DEVICE\",\"id\":\"" + deviceId.getId() + "\"}," +
                "\"msgType\":null,\"msgBody\":{\"entityType\":\"OTA_PACKAGE\",\"id\":\"" + otaId.getId() + "\"},\"metadata\":null}", 150);
        stubAndDeliver(event);

        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceService, timeout(3000)).saveDevice(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getFirmwareId()).isEqualTo(otaId);
        verify(clusterService, timeout(3000)).pushMsgToRuleEngine(eq(tenantId), eq(deviceId), any(TbMsg.class), isNull());
    }

    @Test
    void timerRepeat_reArmsAfterFiringError() {
        DeviceId deviceId = new DeviceId(UUID.randomUUID());
        long start = System.currentTimeMillis() + 200;
        SchedulerEvent event = event("custom", "{\"originatorId\":{\"entityType\":\"DEVICE\",\"id\":\"" + deviceId.getId() + "\"}," +
                "\"msgType\":null,\"msgBody\":{},\"metadata\":null}", 0);
        event.setSchedule(JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + start +
                ",\"repeat\":{\"type\":\"TIMER\",\"repeatInterval\":1,\"timeUnit\":\"SECONDS\",\"endsOn\":" + (start + 10_000) + "}}"));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).doNothing()
                .when(clusterService).pushMsgToRuleEngine(any(TenantId.class), any(EntityId.class), any(TbMsg.class), any());
        stubAndDeliver(event);
        // first fire throws, engine must survive and fire again ~1s later
        verify(clusterService, timeout(5000).atLeast(2)).pushMsgToRuleEngine(eq(tenantId), eq(deviceId), any(TbMsg.class), isNull());
    }

    @Test
    void deletedQueueMsg_cancelsScheduledFire() {
        SchedulerEvent event = event("custom", "{\"originatorId\":null,\"msgType\":null,\"msgBody\":{},\"metadata\":null}", 1500);
        stubAndDeliver(event);
        TransportProtos.SchedulerServiceMsgProto deleteProto = TransportProtos.SchedulerServiceMsgProto.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setEventIdMSB(eventId.getId().getMostSignificantBits())
                .setEventIdLSB(eventId.getId().getLeastSignificantBits())
                .setDeleted(true).build();
        service.onQueueMsg(deleteProto, mock(TbCallback.class));
        // wait past the original fire time: nothing may fire
        await().during(2500, TimeUnit.MILLISECONDS).atMost(4, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(clusterService, never()).pushMsgToRuleEngine(any(TenantId.class), any(EntityId.class), any(TbMsg.class), any()));
    }

    @Test
    void crudPropagation_pushesProtoToCore() {
        SchedulerEvent event = event("custom", "{\"originatorId\":null,\"msgType\":null,\"msgBody\":{},\"metadata\":null}", 60_000);
        service.onSchedulerEventAdded(event);
        ArgumentCaptor<TransportProtos.ToCoreMsg> captor = ArgumentCaptor.forClass(TransportProtos.ToCoreMsg.class);
        verify(clusterService).pushMsgToCore(eq(tenantId), eq(tenantId), captor.capture(), any());
        assertThat(captor.getValue().hasSchedulerServiceMsg()).isTrue();
        assertThat(captor.getValue().getSchedulerServiceMsg().getAdded()).isTrue();
    }
}
