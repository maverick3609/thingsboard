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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.ListenableFuture;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.OtaPackageInfo;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.OtaPackageId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TbCallback;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.dao.scheduler.BaseSchedulerEventService;
import org.thingsboard.server.dao.scheduler.SchedulerEventService;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.TbQueueCallback;
import org.thingsboard.server.queue.TbQueueMsgMetadata;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.ota.OtaPackageStateService;
import org.thingsboard.server.service.partition.AbstractPartitionBasedService;
import org.thingsboard.server.service.scheduler.report.SchedulerReportExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@TbCoreComponent
@Service
@RequiredArgsConstructor
public class DefaultSchedulerService extends AbstractPartitionBasedService<TenantId> implements SchedulerService {

    private final TenantService tenantService;
    private final TbClusterService clusterService;
    private final PartitionService partitionService;
    private final SchedulerEventService schedulerEventService;
    private final OtaPackageStateService firmwareStateService;
    private final DeviceService deviceService;
    private final DeviceProfileService deviceProfileService;
    private final OtaPackageService otaPackageService;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final Optional<SchedulerReportExecutor> schedulerReportExecutor;

    @Value("${server.rest.server_side_rpc.min_timeout:5000}")
    protected long minTimeout;

    private final ConcurrentMap<TenantId, List<SchedulerEventId>> tenantEvents = new ConcurrentHashMap<>();
    private final ConcurrentMap<SchedulerEventId, SchedulerEventMetaData> eventsMetaData = new ConcurrentHashMap<>();
    volatile boolean firstRun = true;
    private String serviceId;

    @PostConstruct
    public void init() {
        super.init();
        this.serviceId = this.serviceInfoProvider.getServiceId();
    }

    @PreDestroy
    public void stop() {
        super.stop();
    }

    @Override
    protected String getServiceName() {
        return "Scheduler";
    }

    @Override
    protected String getSchedulerExecutorName() {
        return "scheduler-service";
    }

    @Override
    public void onSchedulerEventAdded(SchedulerEventInfo event) {
        this.sendSchedulerEvent(event.getTenantId(), event.getId(), true, false, false);
    }

    @Override
    public void onSchedulerEventUpdated(SchedulerEventInfo event) {
        this.sendSchedulerEvent(event.getTenantId(), event.getId(), false, true, false);
    }

    @Override
    public void onSchedulerEventDeleted(SchedulerEventInfo event) {
        this.sendSchedulerEvent(event.getTenantId(), event.getId(), false, false, true);
    }

    @Override
    public void onQueueMsg(TransportProtos.SchedulerServiceMsgProto proto, TbCallback callback) {
        log.debug("onQueueMsg proto {}", proto);
        TenantId tenantId = TenantId.fromUUID(new UUID(proto.getTenantIdMSB(), proto.getTenantIdLSB()));
        SchedulerEventId eventId = new SchedulerEventId(new UUID(proto.getEventIdMSB(), proto.getEventIdLSB()));
        if (proto.getDeleted()) {
            this.onEventDeleted(eventId);
        } else {
            SchedulerEventInfo event = this.schedulerEventService.findSchedulerEventInfoById(tenantId, eventId);
            if (event != null) {
                if (proto.getAdded() && !this.eventsMetaData.containsKey(event.getId())) {
                    this.scheduleAndAddToMap(event);
                } else {
                    SchedulerEventMetaData oldMd = this.eventsMetaData.remove(event.getId());
                    if (oldMd != null && oldMd.getNextTaskFuture() != null) {
                        oldMd.getNextTaskFuture().cancel(false);
                    }
                    this.scheduleAndAddToMap(event);
                }
            }
        }
        callback.onSuccess();
    }

    @Override
    protected Map<TopicPartitionInfo, List<ListenableFuture<?>>> onAddedPartitions(Set<TopicPartitionInfo> addedPartitions) {
        log.info("Scheduler service {}", (this.firstRun ? "Initializing" : "Updating"));
        long ts = System.currentTimeMillis();
        PageDataIterable<Tenant> tenantIterator = new PageDataIterable<>(this.tenantService::findTenants, 1024);
        for (Tenant tenant : tenantIterator) {
            TopicPartitionInfo tpi = this.partitionService.resolve(ServiceType.TB_CORE, tenant.getId(), tenant.getId());
            if (!addedPartitions.contains(tpi)) {
                continue;
            }
            this.addToPartitionedTenants(tenant, tpi);
            this.addEventsForTenant(ts, tenant);
        }
        log.info("Scheduler service {}.", (this.firstRun ? "initialized" : "updated"));
        this.firstRun = false;
        return Collections.emptyMap();
    }

    @Override
    protected void cleanupEntityOnPartitionRemoval(TenantId entityId) {
        this.removeEvents(entityId);
    }

    void removeEvents(TenantId tenantId) {
        this.tenantEvents.getOrDefault(tenantId, Collections.emptyList()).forEach(this::onEventDeleted);
        this.tenantEvents.remove(tenantId);
    }

    void addToPartitionedTenants(Tenant tenant, TopicPartitionInfo tpi) {
        this.partitionedEntities.computeIfAbsent(tpi, key -> ConcurrentHashMap.newKeySet()).add(tenant.getId());
    }

    private void addEventsForTenant(long ts, Tenant tenant) {
        log.debug("[{}] Fetching scheduled events for tenant.", tenant.getId());
        List<SchedulerEventId> eventIds = new ArrayList<>();
        List<SchedulerEventInfo> events = this.schedulerEventService.findSchedulerEventsByTenantIdAndEnabled(tenant.getId(), true);
        long scheduled = 0L;
        long passedAway = 0L;
        long failed = 0L;
        for (SchedulerEventInfo event : events) {
            // Per-event guard: toDescriptor() dereferences schedule.startTime/timezone, so a single row
            // with a null or malformed schedule used to throw out of this loop and leave every REMAINING
            // event of this tenant - and every tenant still to be processed in this partition - silently
            // unscheduled. One bad row now costs only itself.
            try {
                SchedulerEventMetaData md = this.getSchedulerEventMetaData(event);
                if (!md.getDescriptor().passedAway(ts)) {
                    this.eventsMetaData.put(event.getId(), md);
                    eventIds.add(event.getId());
                    ++scheduled;
                } else {
                    ++passedAway;
                    log.warn("[{}][{}] Scheduler event '{}' is not scheduled: its schedule has already ended. " +
                            "Set a new end date ('Ends on') to resume it.", tenant.getId(), event.getId(), event.getName());
                }
                this.scheduleNextEvent(ts, event, md);
            } catch (Exception e) {
                ++failed;
                log.error("[{}][{}] Failed to schedule event '{}'; skipping it.", tenant.getId(), event.getId(), event.getName(), e);
            }
        }
        this.tenantEvents.put(tenant.getId(), eventIds);
        if (passedAway > 0 || failed > 0) {
            log.warn("[{}] Fetched scheduled events for tenant. Scheduling {} events. Found {} passed events, {} failed.",
                    tenant.getId(), scheduled, passedAway, failed);
        } else {
            log.debug("[{}] Fetched scheduled events for tenant. Scheduling {} events. Found {} passed events.", tenant.getId(), scheduled, passedAway);
        }
    }

    private void scheduleNextEvent(long ts, SchedulerEventInfo event, SchedulerEventMetaData md) {
        if (!event.isEnabled()) {
            return;
        }
        long eventTs = md.getDescriptor().getNextEventTime(ts);
        if (eventTs != 0L) {
            log.debug("schedule next event for ts {}, event {}, metadata {}", ts, event, md);
            long eventDelay = eventTs - ts;
            md.setNextTaskFuture(this.scheduledExecutor.schedule(() -> this.processEvent(event.getTenantId(), event.getId()), eventDelay, TimeUnit.MILLISECONDS));
        }
    }

    private SchedulerEventMetaData getSchedulerEventMetaData(SchedulerEventInfo event) {
        return new SchedulerEventMetaData(event.toDescriptor());
    }

    private void processEvent(TenantId tenantId, SchedulerEventId eventId) {
        log.debug("processEvent tenant {}, event {}", tenantId, eventId);
        SchedulerEventMetaData md = this.eventsMetaData.get(eventId);
        if (md != null) {
            SchedulerEvent event = this.schedulerEventService.findSchedulerEventById(tenantId, eventId);
            if (event != null) {
                log.info("[{}][{}] Firing scheduler event", tenantId, eventId);
                try {
                    JsonNode configuration = event.getConfiguration();
                    String msgType = this.getMsgType(event, configuration);
                    EntityId originatorId = BaseSchedulerEventService.getOriginatorId(event);
                    boolean isFirmwareUpdate = "updateFirmware".equals(event.getType());
                    boolean isSoftwareUpdate = "updateSoftware".equals(event.getType());
                    if (isFirmwareUpdate || isSoftwareUpdate) {
                        OtaPackageId firmwareId = JacksonUtil.convertValue(configuration.get("msgBody"), OtaPackageId.class);
                        OtaPackageInfo firmwareInfo = this.otaPackageService.findOtaPackageInfoById(tenantId, firmwareId);
                        if (firmwareInfo == null) {
                            throw new RuntimeException("Failed to process event: OtaPackage with id [" + firmwareId + "] not found!");
                        }
                        switch (originatorId.getEntityType()) {
                            case DEVICE -> {
                                Device device = this.deviceService.findDeviceById(tenantId, (DeviceId) originatorId);
                                if (device == null) {
                                    throw new RuntimeException("Failed to process event: Device with id [" + originatorId + "] not found!");
                                }
                                if (isFirmwareUpdate) {
                                    device.setFirmwareId(firmwareId);
                                } else {
                                    device.setSoftwareId(firmwareId);
                                }
                                this.deviceService.saveDevice(device);
                            }
                            case DEVICE_PROFILE -> {
                                DeviceProfile deviceProfile = this.deviceProfileService.findDeviceProfileById(tenantId, (DeviceProfileId) originatorId);
                                if (deviceProfile == null) {
                                    throw new RuntimeException("Failed to process event: Device profile with id [" + originatorId + "] not found!");
                                }
                                if (isFirmwareUpdate) {
                                    deviceProfile.setFirmwareId(firmwareId);
                                } else {
                                    deviceProfile.setSoftwareId(firmwareId);
                                }
                                this.firmwareStateService.update(this.deviceProfileService.saveDeviceProfile(deviceProfile), isFirmwareUpdate, isSoftwareUpdate);
                            }
                            default -> throw new RuntimeException("Not implemented!");
                        }
                    }
                    if ("generateReport".equals(event.getType())) {
                        schedulerReportExecutor.ifPresentOrElse(
                                executor -> executor.executeReport(tenantId, event),
                                () -> log.warn("[{}][{}] 'generateReport' event fired but the report renderer is disabled. " +
                                        "Set reports.renderer.enabled=true (env REPORTS_RENDERER_ENABLED=true) on a " +
                                        "headless-Chromium-capable host to enable scheduled report generation.", tenantId, eventId));
                    } else if ("generateDashboardReport".equals(event.getType()) && schedulerReportExecutor.isPresent()) {
                        // Direct execution, no rule-chain hop. PE only ever routes this type through the
                        // rule engine, which means the event does nothing until a tenant wires
                        // Message Type Switch -> "Generate Report" -> the "generate dashboard report"
                        // node. Taking the executor when it exists is what makes these events fire out
                        // of the box; when the renderer is off there is no executor and no working node
                        // either, so we fall through to the rule-engine push below and behave exactly as
                        // before. The node stays reachable for non-scheduler messages.
                        schedulerReportExecutor.get().executeDashboardReport(tenantId, event);
                    } else {
                        TbMsgMetaData tbMsgMD = this.getTbMsgMetaData(event, configuration);
                        TbMsg tbMsg = TbMsg.newMsg().type(msgType).originator(originatorId).metaData(tbMsgMD).dataType(TbMsgDataType.JSON).data(this.getMsgBody(event.getConfiguration())).build();
                        log.debug("pushing message to the rule engine tenant {}, originator {}, msg {}", tenantId, originatorId, tbMsg);
                        this.clusterService.pushMsgToRuleEngine(tenantId, originatorId, tbMsg, null);
                    }
                } catch (Exception e) {
                    log.error("[{}][{}] Failed to trigger event", event.getTenantId(), eventId, e);
                }
                this.scheduleNextEvent(System.currentTimeMillis(), event, md);
            } else {
                log.debug("[{}] Triggered event is not present in the database.", eventId);
                this.eventsMetaData.remove(eventId);
            }
        } else {
            log.debug("[{}] Triggered processing of removed event.", eventId);
        }
    }

    private String getMsgBody(JsonNode configuration) throws JsonProcessingException {
        return JacksonUtil.toString(configuration.get("msgBody"));
    }

    /**
     * PE-identical, plus one compatibility fallback (see {@link #defaultMsgType}).
     */
    private String getMsgType(SchedulerEvent event, JsonNode configuration) {
        String msgType = configuration.has("msgType") && !configuration.get("msgType").isNull()
                ? configuration.get("msgType").asText() : defaultMsgType(event);
        return msgType.equals("generateDashboardReport") ? TbMsgType.generateReport.name() : msgType;
    }

    /**
     * DEVIATION from PE, and the reason the scheduler needs no rule chain of its own.
     * <p>
     * PE returns {@code event.getType()} here, which is safe for PE only because PE's scheduler UI
     * always writes an explicit {@code configuration.msgType}: {@code POST_ATTRIBUTES_REQUEST} for
     * {@code updateAttributes} and {@code RPC_CALL_FROM_SERVER_TO_DEVICE} for {@code sendRpcRequest}
     * (verified in {@code ui-ngx-4.2.0PE.jar}, chunk-LQGPECC4.js). Those are stock {@link TbMsgType}s
     * that the DEFAULT root rule chain already routes — "Post attributes" -> Save Client Attributes
     * (which takes its scope from {@code metadata.scope}) and "RPC Request to Device" -> RPC Call
     * Request — so a PE scheduler event works out of the box.
     * <p>
     * Our UI wrote {@code msgType: null}, so this fell back to the raw event type ({@code
     * "updateAttributes"}), which is not a {@link TbMsgType} at all: the Message Type Switch dropped it
     * down its {@code Other} branch into a Log node and the event silently did nothing unless the
     * tenant hand-wired a chain for it. Mapping the known types here rather than only in the UI fixes
     * every ALREADY-STORED event too, with no migration. Unknown/custom types still fall through to
     * the event type exactly as in PE.
     */
    private static String defaultMsgType(SchedulerEvent event) {
        return switch (event.getType()) {
            case "updateAttributes" -> TbMsgType.POST_ATTRIBUTES_REQUEST.name();
            case "sendRpcRequest" -> TbMsgType.RPC_CALL_FROM_SERVER_TO_DEVICE.name();
            default -> event.getType();
        };
    }

    private TbMsgMetaData getTbMsgMetaData(SchedulerEvent event, JsonNode configuration) throws JsonProcessingException {
        Map<String, String> metaData = new HashMap<>();
        if (configuration.has("metadata") && !configuration.get("metadata").isNull()) {
            Iterator<Map.Entry<String, JsonNode>> it = configuration.get("metadata").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> kv = it.next();
                metaData.put(kv.getKey(), kv.getValue().asText());
            }
        } else {
            metaData.put("customerId", event.getCustomerId().getId().toString());
            metaData.put("eventName", event.getName());
            if (event.getAdditionalInfo() != null) {
                metaData.put("additionalInfo", JacksonUtil.toString(event.getAdditionalInfo()));
            }
        }
        if ("sendRpcRequest".equals(event.getType())) {
            metaData.put("originServiceId", this.serviceId);
            if (metaData.get("timeout") != null) {
                metaData.put("expirationTime", String.valueOf(System.currentTimeMillis() + Math.max(this.minTimeout, Long.parseLong(metaData.get("timeout")))));
            }
        }
        return new TbMsgMetaData(metaData);
    }

    void onEventDeleted(SchedulerEventId eventId) {
        log.debug("onEventDeleted event {}", eventId);
        SchedulerEventMetaData oldMd = this.eventsMetaData.remove(eventId);
        if (oldMd != null && oldMd.getNextTaskFuture() != null) {
            oldMd.getNextTaskFuture().cancel(false);
        }
    }

    private void scheduleAndAddToMap(SchedulerEventInfo event) {
        log.debug("scheduleAndAddToMap event {}", event);
        long ts = System.currentTimeMillis();
        SchedulerEventMetaData eventMd = this.getSchedulerEventMetaData(event);
        this.eventsMetaData.put(event.getId(), eventMd);
        this.scheduleNextEvent(ts, event, eventMd);
    }

    private void sendSchedulerEvent(TenantId tenantId, SchedulerEventId eventId, boolean added, boolean updated, boolean deleted) {
        log.trace("sendSchedulerEvent tenantId {}, eventId {}, added {}, updated {}, deleted {}", tenantId, eventId, added, updated, deleted);
        TransportProtos.SchedulerServiceMsgProto.Builder builder = TransportProtos.SchedulerServiceMsgProto.newBuilder();
        builder.setTenantIdMSB(tenantId.getId().getMostSignificantBits());
        builder.setTenantIdLSB(tenantId.getId().getLeastSignificantBits());
        builder.setEventIdMSB(eventId.getId().getMostSignificantBits());
        builder.setEventIdLSB(eventId.getId().getLeastSignificantBits());
        builder.setAdded(added);
        builder.setUpdated(updated);
        builder.setDeleted(deleted);
        TransportProtos.SchedulerServiceMsgProto msg = builder.build();
        log.trace("msg {}", msg);
        TransportProtos.ToCoreMsg toCoreMsg = TransportProtos.ToCoreMsg.newBuilder().setSchedulerServiceMsg(msg).build();
        log.trace("toCoreMsg.hasSchedulerServiceMsg() {} toCoreMsg {}", toCoreMsg.hasSchedulerServiceMsg(), toCoreMsg);
        this.clusterService.pushMsgToCore(tenantId, tenantId, toCoreMsg, new TbQueueCallback() {

            @Override
            public void onSuccess(TbQueueMsgMetadata metadata) {
                log.trace("sendSchedulerEvent onSuccess tenantId {}, eventId {}, added {}, updated {}, deleted {}", tenantId, eventId, added, updated, deleted);
            }

            @Override
            public void onFailure(Throwable t) {
                log.trace("sendSchedulerEvent onFailure tenantId {}, eventId {}, added {}, updated {}, deleted {}", tenantId, eventId, added, updated, deleted, t);
            }
        });
    }

}
