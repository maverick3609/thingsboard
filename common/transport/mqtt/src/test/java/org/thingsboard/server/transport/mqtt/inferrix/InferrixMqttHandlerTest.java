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
package org.thingsboard.server.transport.mqtt.inferrix;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.mqtt.MqttTransportContext;
import org.thingsboard.server.transport.mqtt.adaptors.JsonMqttAdaptor;
import org.thingsboard.server.transport.mqtt.session.DeviceSessionCtx;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InferrixMqttHandlerTest {

    private static final String ROOT = "com/inferrix";
    private static final String UID = "aabbccddeeff001122334455";

    @Mock
    private DeviceSessionCtx ctx;
    @Mock
    private MqttTransportContext transportContext;
    @Mock
    private TransportService transportService;
    @Mock
    private ChannelHandlerContext channel;
    @Mock
    private TransportServiceCallback<Void> callback;

    private InferrixMqttHandler handler;

    @BeforeEach
    void setUp() {
        when(ctx.getSessionId()).thenReturn(UUID.randomUUID());
        when(ctx.getSessionInfo()).thenReturn(TransportProtos.SessionInfoProto.getDefaultInstance());
        when(ctx.getChannel()).thenReturn(channel);
        when(ctx.getContext()).thenReturn(transportContext);
        when(ctx.getQoSForTopic(any())).thenReturn(MqttQoS.AT_LEAST_ONCE);
        when(ctx.nextMsgId()).thenReturn(1);
        when(transportContext.getJsonMqttAdaptor()).thenReturn(new JsonMqttAdaptor());
        handler = new InferrixMqttHandler(ctx, transportService, ROOT);
    }

    @Test
    void ownsOnlyTopicsUnderTheRoot() {
        assertTrue(handler.owns(ROOT + "/telemetry/" + UID));
        assertTrue(handler.owns(ROOT + "/discovery"));
        assertFalse(handler.owns(ROOT));
        assertFalse(handler.owns("v1/devices/me/telemetry"));
        assertFalse(handler.owns("com/inferrixother/telemetry/" + UID));
    }

    @Test
    void telemetryIsKeyedByPointIdAndCarriesQualityOnlyWhenDegraded() {
        publish(ROOT + "/telemetry/" + UID, """
                {"ts":1699999999000,"tq":"synced","points":[
                  {"id":1,"type":"di","v":false,"q":"good","age_ms":77,"n":"DI1"},
                  {"id":13,"type":"ai","v":23.5,"q":"good","age_ms":77,"n":"AI1"},
                  {"id":20,"type":"rtu","v":100,"q":"stale","age_ms":9000,"n":"FlowRate"},
                  {"id":21,"type":"rtu","v":0,"q":"comm_fail","age_ms":60000,"n":"Pump"},
                  {"id":22,"type":"rtu","q":"never","age_ms":0,"n":"Valve"}
                ]}""");

        TransportProtos.PostTelemetryMsg msg = captureTelemetry();
        assertEquals(1, msg.getTsKvListCount());
        TransportProtos.TsKvListProto list = msg.getTsKvList(0);
        assertEquals(1699999999000L, list.getTs());

        Map<String, TransportProtos.KeyValueProto> kv = index(list);
        assertEquals(false, kv.get("p1").getBoolV());
        assertEquals(23.5, kv.get("p13").getDoubleV());
        // stale keeps the reading and says so
        assertEquals(100L, kv.get("p20").getLongV());
        assertEquals("stale", kv.get("p20_q").getStringV());
        // the device says these two values mean nothing, so only the quality is stored
        assertNull(kv.get("p21"));
        assertEquals("comm_fail", kv.get("p21_q").getStringV());
        assertNull(kv.get("p22"));
        assertEquals("never", kv.get("p22_q").getStringV());
        // good points cost exactly one series each
        assertNull(kv.get("p1_q"));
        assertNull(kv.get("p13_q"));
    }

    @Test
    void telemetryWithoutDeviceTimeFallsBackToServerTime() {
        long before = System.currentTimeMillis();
        publish(ROOT + "/telemetry/" + UID, """
                {"points":[{"id":1,"type":"di","v":true,"q":"good","age_ms":5,"n":"DI1"}]}""");

        long ts = captureTelemetry().getTsKvList(0).getTs();
        assertTrue(ts >= before && ts <= System.currentTimeMillis(), "expected server time, got " + ts);
    }

    @Test
    void emptyOrUnparseableTelemetryIsAcknowledgedAndDropped() {
        publish(ROOT + "/telemetry/" + UID, "{\"points\":[]}");
        publish(ROOT + "/telemetry/" + UID, "not json at all");

        verify(transportService, never()).process(any(), any(TransportProtos.PostTelemetryMsg.class), any(), any());
        verify(callback, never()).onError(any());
    }

    @Test
    void healthFlattensNestedScalarsAndKeepsArraysAsJson() {
        publish(ROOT + "/health/" + UID, """
                {"uptime_ms":123456,"scan":{"last_ms":3,"max_ms":11,"overruns":2},
                 "mqtt":{"state":2,"coalesced":7},
                 "stacks":[{"n":"main","size":2048,"free":900}]}""");

        Map<String, TransportProtos.KeyValueProto> kv = index(captureTelemetry().getTsKvList(0));
        assertEquals(123456L, kv.get("uptime_ms").getLongV());
        assertEquals(11L, kv.get("scan_max_ms").getLongV());
        assertEquals(2L, kv.get("scan_overruns").getLongV());
        assertEquals(2L, kv.get("mqtt_state").getLongV());
        assertEquals(TransportProtos.KeyValueType.JSON_V, kv.get("stacks").getType());
    }

    @Test
    void identityBecomesAttributesAndTheSharedAnnounceIsAcked() {
        publish(ROOT + "/discovery", """
                {"uid":"%s","model":"infx-ctrl-h750","fw":0,"icc":7,
                 "ip":"192.168.1.150","name":"AHU-1","location":"Roof"}""".formatted(UID));

        ArgumentCaptor<TransportProtos.PostAttributeMsg> captor =
                ArgumentCaptor.forClass(TransportProtos.PostAttributeMsg.class);
        verify(transportService).process(any(), captor.capture(), any(TbMsgMetaData.class), eq(callback));
        Map<String, TransportProtos.KeyValueProto> kv = new HashMap<>();
        captor.getValue().getKvList().forEach(entry -> kv.put(entry.getKey(), entry));
        assertEquals("infx-ctrl-h750", kv.get("model").getStringV());
        assertEquals("Roof", kv.get("location").getStringV());

        // the announce repeats every 5 minutes until something answers on the ack topic
        assertEquals(ROOT + "/discovery/" + UID + "/ack", capturePublishedTopic());
    }

    @Test
    void timeSyncRequestIsAnsweredWithTheEchoAndCurrentWallClock() {
        long before = System.currentTimeMillis();
        publish(ROOT + "/timesync/" + UID + "/req", "{\"echo\":123456789}");

        MqttPublishMessage sent = capturePublished();
        assertEquals(ROOT + "/timesync/" + UID, sent.variableHeader().topicName());
        JsonObject reply = JsonParser.parseString(sent.payload().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(123456789L, reply.get("echo").getAsLong());
        long epoch = reply.get("epoch_ms").getAsLong();
        assertTrue(epoch >= before && epoch <= System.currentTimeMillis(), "expected wall clock, got " + epoch);
        // nothing to store: a time request is not data
        verify(transportService).recordActivity(any());
    }

    @Test
    void subscribeAcceptsTheThreeDownlinkTopicsAndRejectsAnythingElse() {
        assertTrue(handler.onSubscribe(ROOT + "/command/" + UID));
        assertTrue(handler.onSubscribe(ROOT + "/timesync/" + UID));
        assertTrue(handler.onSubscribe(ROOT + "/discovery/" + UID + "/ack"));
        // an uplink topic is not something the device may subscribe to
        assertFalse(handler.onSubscribe(ROOT + "/telemetry/" + UID));
        assertFalse(handler.onSubscribe("v1/devices/me/attributes"));

        // subscribing to the command topic is what makes the device eligible for TB RPC
        verify(transportService).process(any(), any(TransportProtos.SubscribeToRPCMsg.class), any());
    }

    @Test
    void rpcIsPublishedVerbatimToTheCommandTopicAndTheResultCorrelatesBack() {
        handler.onSubscribe(ROOT + "/command/" + UID);
        String envelope = "{\"token\":\"deadbeef\",\"cmd\":\"set\",\"target\":{\"type\":\"do\",\"id\":9},\"v\":true}";

        assertTrue(handler.onRpcRequest(TransportProtos.ToDeviceRpcRequestMsg.newBuilder()
                .setRequestId(42).setMethodName("inferrix").setParams(envelope).build()));

        MqttPublishMessage sent = capturePublished();
        assertEquals(ROOT + "/command/" + UID, sent.variableHeader().topicName());
        assertEquals(envelope, sent.payload().toString(StandardCharsets.UTF_8));

        publish(ROOT + "/command/" + UID + "/result", "{\"cmd\":\"set\",\"ok\":true}");
        ArgumentCaptor<TransportProtos.ToDeviceRpcResponseMsg> captor =
                ArgumentCaptor.forClass(TransportProtos.ToDeviceRpcResponseMsg.class);
        verify(transportService).process(any(), captor.capture(), eq(callback));
        assertEquals(42, captor.getValue().getRequestId());
        assertEquals("{\"cmd\":\"set\",\"ok\":true}", captor.getValue().getPayload());
    }

    @Test
    void aUidThatCouldRestructureATopicIsRefused() {
        // a device naming a uid with a path separator, a wildcard or whitespace must not steer our
        // downlink topic, so nothing is learned and the time reply is suppressed
        publish(ROOT + "/timesync/" + UID + "/../evil/req", "{\"echo\":1}");
        verify(channel, never()).writeAndFlush(any());

        // the well-formed uid is accepted and the reply lands on the topic built from it
        publish(ROOT + "/timesync/" + UID + "/req", "{\"echo\":1}");
        assertEquals(ROOT + "/timesync/" + UID, capturePublishedTopic());
    }

    @Test
    void selfGeneratedDownlinkIsFlooredSoAFloodCannotBypassTheUplinkRateLimiter() {
        for (int i = 0; i < 20; i++) {
            publish(ROOT + "/timesync/" + UID + "/req", "{\"echo\":" + i + "}");
        }
        // the reply is generated here rather than routed through transportService, so nothing else
        // meters it; exactly one gets out inside the floor window
        verify(channel).writeAndFlush(any());
    }

    @Test
    void aNonNumericPointIdIsSkippedWithoutLosingTheRestOfTheBatch() {
        publish(ROOT + "/telemetry/" + UID, """
                {"ts":1699999999000,"points":[
                  {"id":"nope","type":"di","v":true,"q":"good","age_ms":1,"n":"Bad"},
                  {"id":13,"type":"ai","v":23.5,"q":"good","age_ms":77,"n":"AI1"}
                ]}""");

        Map<String, TransportProtos.KeyValueProto> kv = index(captureTelemetry().getTsKvList(0));
        assertEquals(1, kv.size());
        assertEquals(23.5, kv.get("p13").getDoubleV());
    }

    @Test
    void rpcIsRefusedBeforeTheUidIsKnownAndWhenTheEnvelopeIsNotAnObject() {
        assertFalse(handler.onRpcRequest(TransportProtos.ToDeviceRpcRequestMsg.newBuilder()
                .setRequestId(1).setParams("{}").build()), "no uid learned yet");

        handler.onSubscribe(ROOT + "/command/" + UID);
        assertFalse(handler.onRpcRequest(TransportProtos.ToDeviceRpcRequestMsg.newBuilder()
                .setRequestId(2).setParams("\"just a string\"").build()),
                "the device would silently drop a non-object envelope");
    }

    // --- helpers ---

    private void publish(String topic, String payload) {
        MqttPublishMessage msg = new MqttPublishMessage(
                new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_MOST_ONCE, false, 0),
                new MqttPublishVariableHeader(topic, 0),
                Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8)));
        handler.onPublish(topic, msg, new TbMsgMetaData(), callback);
    }

    private TransportProtos.PostTelemetryMsg captureTelemetry() {
        ArgumentCaptor<TransportProtos.PostTelemetryMsg> captor =
                ArgumentCaptor.forClass(TransportProtos.PostTelemetryMsg.class);
        verify(transportService).process(any(), captor.capture(), any(TbMsgMetaData.class), eq(callback));
        return captor.getValue();
    }

    private MqttPublishMessage capturePublished() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(channel).writeAndFlush(captor.capture());
        Object sent = captor.getValue();
        assertNotNull(sent);
        return (MqttPublishMessage) sent;
    }

    private String capturePublishedTopic() {
        return capturePublished().variableHeader().topicName();
    }

    private static Map<String, TransportProtos.KeyValueProto> index(TransportProtos.TsKvListProto list) {
        Map<String, TransportProtos.KeyValueProto> byKey = new HashMap<>();
        list.getKvList().forEach(kv -> byKey.put(kv.getKey(), kv));
        return byKey;
    }

}
