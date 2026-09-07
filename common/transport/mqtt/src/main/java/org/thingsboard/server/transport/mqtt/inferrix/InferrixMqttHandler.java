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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.adaptor.JsonConverter;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.mqtt.session.DeviceSessionCtx;

import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bridges the Inferrix soft-PLC controller's MQTT surface onto the ThingsBoard transport.
 *
 * <p>The controller (docs/asyncapi.yaml, docs/INTEGRATION-API.md §4 in the inferrix-controller
 * repo) speaks a fixed topic scheme rooted at a configurable prefix, default {@code com/inferrix}:
 *
 * <pre>
 *   &lt;root&gt;/telemetry/&lt;uid&gt;         pub   batched point telemetry
 *   &lt;root&gt;/health/&lt;uid&gt;            pub   resource snapshot
 *   &lt;root&gt;/discovery/&lt;uid&gt;         pub   identity + status
 *   &lt;root&gt;/discovery                pub   shared registration announce (until acked)
 *   &lt;root&gt;/discovery/&lt;uid&gt;/ack     sub   any payload stops the announce
 *   &lt;root&gt;/timesync/&lt;uid&gt;/req      pub   time request, echo token
 *   &lt;root&gt;/timesync/&lt;uid&gt;          sub   time reply
 *   &lt;root&gt;/command/&lt;uid&gt;           sub   commands and point writes
 *   &lt;root&gt;/command/&lt;uid&gt;/result    pub   result for each authenticated command
 * </pre>
 *
 * <p>ThingsBoard's per-device-profile topic filters cover the uplink half but downlink on a custom
 * topic is impossible natively — a custom-filter SUBSCRIBE is pinned to {@code TopicType.V1} and
 * every push therefore goes to {@code v1/devices/me/attributes}. Timesync and point writes both
 * need real downlink, so they are handled here.
 *
 * <p>Instantiated only when the device profile carries a non-blank
 * {@code inferrixTopicRoot}; every other MQTT device never reaches this class.
 *
 * <p>Threading: {@link #onPublish} and {@link #onSubscribe} run on the channel's Netty event loop;
 * {@link #onRpcRequest} runs on a transport callback thread. Shared state is therefore volatile or
 * concurrent.
 */
@Slf4j
public class InferrixMqttHandler {

    private static final String TELEMETRY = "telemetry";
    private static final String HEALTH = "health";
    private static final String DISCOVERY = "discovery";
    private static final String TIMESYNC = "timesync";
    private static final String COMMAND = "command";

    private static final String REQ_SUFFIX = "/req";
    private static final String RESULT_SUFFIX = "/result";
    private static final String ACK_SUFFIX = "/ack";

    private static final String QUALITY_GOOD = "good";
    private static final String QUALITY_COMM_FAIL = "comm_fail";
    private static final String QUALITY_NEVER = "never";

    /**
     * Cap on RPC request ids awaiting a device result.
     * <p>ponytail: the firmware's command result carries no correlation id (INTEGRATION-API §4.6),
     * so results are matched to requests FIFO. That is exact while one write is outstanding at a
     * time — which is what the Cortex UI does — and mismatches under concurrent writes to the same
     * controller. Upgrade path: add a correlation id to the command envelope firmware-side, then
     * key a map on it instead of this deque.
     */
    private static final int MAX_PENDING_RPC = 16;

    /**
     * The uid is device-supplied — it comes from a topic segment or a discovery payload — and is
     * concatenated into the downlink topics this class publishes on. Accept only what the firmware
     * actually emits (24 lowercase hex) plus a little slack, and nothing that could restructure a
     * topic or smuggle in a wildcard.
     */
    private static final int MAX_UID_LENGTH = 64;

    /**
     * Floor between two self-generated downlink publishes of the same kind. The firmware asks for
     * time once per resync_interval_s (6 h by default) and re-announces every period_s (5 min), so
     * anything above this rate is a broken or hostile device. It matters because these replies are
     * produced here rather than routed through {@code transportService}, and therefore never meet
     * the per-session message rate limiter that every uplink does.
     */
    private static final long DOWNLINK_MIN_INTERVAL_MS = 1000L;

    @Getter
    private final String root;
    private final String prefix;
    private final DeviceSessionCtx ctx;
    private final TransportService transportService;

    private final Deque<Integer> pendingRpc = new ConcurrentLinkedDeque<>();

    /** 24-hex silicon uid, learned from the first topic or payload that carries it. */
    private volatile String uid;

    // Both only ever touched from the channel's event loop, so a plain read-modify-write is safe.
    private long lastTimeSyncReplyMs;
    private long lastRegistrationAckMs;

    public InferrixMqttHandler(DeviceSessionCtx ctx, TransportService transportService, String root) {
        this.ctx = ctx;
        this.transportService = transportService;
        this.root = root;
        this.prefix = root + "/";
    }

    /** True when the topic belongs to this session's Inferrix root. */
    public boolean owns(String topicName) {
        return topicName.length() > prefix.length() && topicName.startsWith(prefix);
    }

    /**
     * Handles one uplink publish. Always completes {@code callback} — a payload this class cannot
     * parse is logged and acknowledged rather than dropped mid-session, because a field controller
     * would otherwise reconnect into the same failure forever.
     */
    public void onPublish(String topicName, MqttPublishMessage mqttMsg, TbMsgMetaData md,
                          TransportServiceCallback<Void> callback) {
        String rest = topicName.substring(prefix.length());
        int slash = rest.indexOf('/');
        String service = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash + 1);

        JsonObject payload;
        try {
            payload = parse(mqttMsg);
        } catch (RuntimeException e) {
            log.debug("[{}] Unparseable Inferrix payload on [{}]", ctx.getSessionId(), topicName, e);
            callback.onSuccess(null);
            return;
        }

        try {
            switch (service) {
                case TELEMETRY -> {
                    rememberUid(tail);
                    processTelemetry(toTelemetry(payload), md, callback);
                }
                case HEALTH -> {
                    rememberUid(tail);
                    processTelemetry(toHealth(payload), md, callback);
                }
                case DISCOVERY -> {
                    rememberUid(tail.isEmpty() ? optString(payload, "uid") : tail);
                    if (tail.isEmpty()) {
                        ackRegistration();
                    }
                    transportService.process(ctx.getSessionInfo(),
                            JsonConverter.convertToAttributesProto(payload), md, callback);
                }
                case TIMESYNC -> {
                    if (tail.endsWith(REQ_SUFFIX)) {
                        rememberUid(tail.substring(0, tail.length() - REQ_SUFFIX.length()));
                        replyTimeSync(payload);
                    }
                    handledLocally(callback);
                }
                case COMMAND -> {
                    if (tail.endsWith(RESULT_SUFFIX)) {
                        rememberUid(tail.substring(0, tail.length() - RESULT_SUFFIX.length()));
                        processCommandResult(mqttMsg, callback);
                    } else {
                        handledLocally(callback);
                    }
                }
                default -> {
                    log.debug("[{}] Unknown Inferrix topic [{}]", ctx.getSessionId(), topicName);
                    handledLocally(callback);
                }
            }
        } catch (RuntimeException e) {
            log.debug("[{}] Failed to handle Inferrix publish on [{}]", ctx.getSessionId(), topicName, e);
            callback.onSuccess(null);
        }
    }

    /**
     * Registers one of the controller's three downlink subscriptions. Returns false for a topic
     * under the Inferrix root that the controller is not expected to subscribe to, so the caller
     * falls through to its own (rejecting) default.
     */
    public boolean onSubscribe(String topic) {
        if (!owns(topic)) {
            return false;
        }
        String rest = topic.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String service = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        switch (service) {
            case COMMAND -> {
                rememberUid(tail);
                // The controller's inbound command topic is where TB RPC requests land, so this
                // subscription is what makes the device eligible for them.
                transportService.process(ctx.getSessionInfo(),
                        TransportProtos.SubscribeToRPCMsg.newBuilder().build(), null);
                return true;
            }
            case TIMESYNC -> {
                rememberUid(tail);
                return true;
            }
            case DISCOVERY -> {
                if (tail.endsWith(ACK_SUFFIX)) {
                    rememberUid(tail.substring(0, tail.length() - ACK_SUFFIX.length()));
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Publishes a TB RPC request onto the controller's command topic verbatim.
     *
     * <p>{@code params} must already be the complete Inferrix command envelope, bearer token
     * included — the firmware silently drops an envelope with a missing or wrong token and the
     * transport deliberately holds no device credentials. Building the envelope is the caller's
     * job. The RPC {@code methodName} is not used.
     *
     * @return false when the request cannot be delivered, so the caller can fail the RPC.
     */
    public boolean onRpcRequest(TransportProtos.ToDeviceRpcRequestMsg rpcRequest) {
        String deviceUid = uid;
        if (deviceUid == null) {
            log.debug("[{}] No Inferrix uid known yet; cannot route RPC {}", ctx.getSessionId(), rpcRequest.getRequestId());
            return false;
        }
        String params = rpcRequest.getParams();
        if (!isJsonObject(params)) {
            log.debug("[{}] RPC params are not a JSON object; refusing to publish a payload the device will drop", ctx.getSessionId());
            return false;
        }
        if (!rpcRequest.getOneway()) {
            while (pendingRpc.size() >= MAX_PENDING_RPC) {
                pendingRpc.poll();
            }
            pendingRpc.add(rpcRequest.getRequestId());
        }
        return publish(prefix + COMMAND + "/" + deviceUid, params);
    }

    // --- uplink conversions ---

    /**
     * {@code {"ts":..,"tq":..,"points":[{"id":1,"type":"di","v":false,"q":"good","age_ms":77,"n":"DI1"}]}}
     *
     * <p>Series are keyed {@code p<id>} rather than by the point's name: the id survives a rename in
     * the config plane, the name does not, and stranding history on a rename is not recoverable
     * whereas adding a display name later is. Quality is only written when it is not {@code good},
     * and a point whose quality says no value exists contributes the quality alone.
     *
     * <p>ponytail: one timestamp for the whole batch, taken from the envelope. Per-point acquisition
     * time ({@code ts - age_ms}) is sub-second on a 100 ms scan and not worth fragmenting the proto
     * into one TsKvList per point. Revisit if a slow bus makes age_ms material. {@code tq} is
     * dropped for the same reason — writing it every batch would be one extra series per sweep.
     */
    private TransportProtos.PostTelemetryMsg toTelemetry(JsonObject jo) {
        JsonObject values = new JsonObject();
        JsonElement pointsElement = jo.get("points");
        if (pointsElement == null || !pointsElement.isJsonArray()) {
            return null;
        }
        for (JsonElement pe : pointsElement.getAsJsonArray()) {
            if (!pe.isJsonObject()) {
                continue;
            }
            JsonObject point = pe.getAsJsonObject();
            JsonElement id = point.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            String key = "p" + id.getAsInt();
            String quality = optString(point, "q");
            if (quality == null) {
                quality = QUALITY_GOOD;
            }
            JsonElement value = point.get("v");
            boolean valueTrustworthy = !QUALITY_COMM_FAIL.equals(quality) && !QUALITY_NEVER.equals(quality);
            if (valueTrustworthy && value != null && value.isJsonPrimitive()) {
                values.add(key, value);
            }
            if (!QUALITY_GOOD.equals(quality)) {
                values.addProperty(key + "_q", quality);
            }
        }
        return values.size() == 0 ? null : withTs(values, optLong(jo, "ts"));
    }

    /**
     * {@code {"uptime_ms":..,"scan":{..},"mqtt":{..},"stacks":[..],"net_pools":[..]}}
     *
     * <p>Nested scalar groups are flattened to {@code scan_max_ms}-style keys so they are alarmable;
     * arrays are left for {@link JsonConverter} to store as JSON.
     */
    private TransportProtos.PostTelemetryMsg toHealth(JsonObject jo) {
        JsonObject values = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : jo.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                for (Map.Entry<String, JsonElement> nested : value.getAsJsonObject().entrySet()) {
                    if (nested.getValue().isJsonPrimitive()) {
                        values.add(entry.getKey() + "_" + nested.getKey(), nested.getValue());
                    }
                }
            } else {
                values.add(entry.getKey(), value);
            }
        }
        return values.size() == 0 ? null : withTs(values, null);
    }

    private TransportProtos.PostTelemetryMsg withTs(JsonObject values, Long ts) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ts", ts != null ? ts : System.currentTimeMillis());
        envelope.add("values", values);
        return JsonConverter.convertToTelemetryProto(envelope);
    }

    // --- downlink ---

    /**
     * Echo the device's token back with the current wall clock; the device has no SNTP. Replies go
     * to the accepted uid rather than the requesting topic's, so a topic this class refused to trust
     * cannot steer the downlink.
     */
    private void replyTimeSync(JsonObject request) {
        String deviceUid = uid;
        JsonElement echo = request.get("echo");
        if (deviceUid == null || echo == null || !echo.isJsonPrimitive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTimeSyncReplyMs < DOWNLINK_MIN_INTERVAL_MS) {
            log.debug("[{}] Dropping time sync request: replies are floored at one per {} ms",
                    ctx.getSessionId(), DOWNLINK_MIN_INTERVAL_MS);
            return;
        }
        lastTimeSyncReplyMs = now;
        JsonObject reply = new JsonObject();
        reply.add("echo", echo);
        reply.addProperty("epoch_ms", now);
        publish(prefix + TIMESYNC + "/" + deviceUid, reply.toString());
    }

    /** Any payload on the ack topic stops the controller's 5-minute registration announce. */
    private void ackRegistration() {
        String deviceUid = uid;
        if (deviceUid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRegistrationAckMs < DOWNLINK_MIN_INTERVAL_MS) {
            return;
        }
        lastRegistrationAckMs = now;
        publish(prefix + DISCOVERY + "/" + deviceUid + ACK_SUFFIX, "{}");
    }

    private boolean publish(String topic, String payload) {
        var channel = ctx.getChannel();
        if (channel == null) {
            return false;
        }
        channel.writeAndFlush(ctx.getContext().getJsonMqttAdaptor()
                .createMqttPublishMsg(ctx, topic, payload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    // --- plumbing ---

    private void processTelemetry(TransportProtos.PostTelemetryMsg msg, TbMsgMetaData md,
                                  TransportServiceCallback<Void> callback) {
        if (msg == null) {
            handledLocally(callback);
        } else {
            transportService.process(ctx.getSessionInfo(), msg, md, callback);
        }
    }

    private void processCommandResult(MqttPublishMessage mqttMsg, TransportServiceCallback<Void> callback) {
        Integer requestId = pendingRpc.poll();
        if (requestId == null) {
            // A result for a command this node did not send (another node, or a retry after the
            // deque was trimmed). Nothing to correlate it to.
            handledLocally(callback);
            return;
        }
        transportService.process(ctx.getSessionInfo(), TransportProtos.ToDeviceRpcResponseMsg.newBuilder()
                .setRequestId(requestId)
                .setPayload(mqttMsg.payload().toString(StandardCharsets.UTF_8))
                .build(), callback);
    }

    /** No transport message to send, but the device did talk to us. */
    private void handledLocally(TransportServiceCallback<Void> callback) {
        transportService.recordActivity(ctx.getSessionInfo());
        callback.onSuccess(null);
    }

    private void rememberUid(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_UID_LENGTH) {
            return;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                log.debug("[{}] Refusing an Inferrix uid that could restructure a topic", ctx.getSessionId());
                return;
            }
        }
        uid = candidate;
    }

    private static JsonObject parse(MqttPublishMessage mqttMsg) {
        return JsonParser.parseString(mqttMsg.payload().toString(StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static boolean isJsonObject(String raw) {
        try {
            return JsonParser.parseString(raw).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String optString(JsonObject jo, String key) {
        JsonElement element = jo.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static Long optLong(JsonObject jo, String key) {
        JsonElement element = jo.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong() : null;
    }

}
