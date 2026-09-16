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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.ControllerResponse;
import org.thingsboard.server.service.inferrix.InferrixProvisionService.Mode;
import org.thingsboard.server.service.inferrix.InferrixProvisionService.ProvisionJob;
import org.thingsboard.server.service.inferrix.InferrixProvisionService.State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InferrixProvisionServiceTest {

    private static final TenantId TENANT = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE = new DeviceId(UUID.randomUUID());

    private InferrixProvisionService service;
    private FakeController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FakeController();
        InferrixControllerAccess access = mock(InferrixControllerAccess.class);
        when(access.call(any(), any(), anyString(), anyString(), any())).thenAnswer(invocation ->
                controller.handle(invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)));
        service = new InferrixProvisionService(access);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void aNeverConfiguredControllerGetsEveryChannelAndTheResultApplied() throws Exception {
        ProvisionJob job = await(service.start(TENANT, DEVICE, Mode.APPLY));

        assertThat(job.getState()).isEqualTo(State.DONE);
        assertThat(job.getAdded()).isEqualTo(24);
        assertThat(job.getIccVersion()).isEqualTo(1L);
        assertThat(job.getActivation()).isEqualTo("hot-swap");
        // Numbered the way the firmware's own examples number this board, in channel order.
        assertThat(controller.points.keySet()).containsExactly(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24);
        assertThat(controller.points.get(1)).isEqualTo(JacksonUtil.toJsonNode("{\"point_id\":1,\"source\":0,"
                + "\"data_format\":6,\"source_ref\":0,\"offset\":0,\"scaling_idx\":65535,\"flags\":0,"
                + "\"refresh_s\":0,\"name\":\"DI1\"}"));
        assertThat(controller.points.get(12).path("name").asText()).isEqualTo("DO4");
        assertThat(controller.points.get(18)).isEqualTo(JacksonUtil.toJsonNode("{\"point_id\":18,\"source\":2,"
                + "\"data_format\":0,\"source_ref\":5,\"offset\":0,\"scaling_idx\":65535,\"flags\":0,"
                + "\"refresh_s\":0,\"name\":\"AI6\"}"));
        assertThat(controller.points.get(19).path("source").asInt()).isEqualTo(3);
        assertThat(controller.policies.get(9)).isEqualTo(JacksonUtil.toJsonNode(
                "{\"point_id\":9,\"trigger\":3,\"qos\":1,\"interval_s\":300,\"deadband_bits\":0}"));
        // 40.0f is 0x42200000.
        assertThat(controller.policies.get(13)).isEqualTo(JacksonUtil.toJsonNode(
                "{\"point_id\":13,\"trigger\":3,\"qos\":0,\"interval_s\":60,\"deadband_bits\":1109393408}"));
        assertThat(controller.policies.get(24)).isEqualTo(JacksonUtil.toJsonNode(
                "{\"point_id\":24,\"trigger\":2,\"qos\":0,\"interval_s\":0,\"deadband_bits\":0}"));
        assertThat(controller.applies).isEqualTo(1);
    }

    @Test
    void theDraftModeFillsOnlyUncoveredChannelsAroundIdsInUseAndNeverApplies() throws Exception {
        controller.io = "{\"di\":2,\"do\":1,\"ai\":1,\"ao\":0}";
        // DI1 already has a point, under an id of its own; ids 2 and 3 belong to a Modbus point and
        // to a policy whose point the operator has not added yet.
        controller.points.put(40, record("{\"point_id\":40,\"source\":0,\"source_ref\":0}"));
        controller.points.put(2, record("{\"point_id\":2,\"source\":4,\"source_ref\":7}"));
        controller.policies.put(3, record("{\"point_id\":3}"));

        ProvisionJob job = await(service.start(TENANT, DEVICE, Mode.DRAFT));

        assertThat(job.getState()).isEqualTo(State.DONE);
        assertThat(job.getAdded()).isEqualTo(3);
        assertThat(controller.points.get(4).path("name").asText()).isEqualTo("DI2");
        assertThat(controller.points.get(5).path("name").asText()).isEqualTo("DO1");
        assertThat(controller.points.get(6).path("name").asText()).isEqualTo("AI1");
        assertThat(controller.points).hasSize(5);
        assertThat(controller.policies.get(3)).isEqualTo(record("{\"point_id\":3}"));
        assertThat(controller.applies).isZero();
    }

    @Test
    void adoptionLeavesAControllerAloneUnlessItIsBlank() throws Exception {
        controller.icc = 4;
        assertThat(await(service.start(TENANT, DEVICE, Mode.APPLY)).getState()).isEqualTo(State.SKIPPED);

        controller.icc = 0;
        controller.owner = "platform";
        assertThat(await(service.start(TENANT, DEVICE, Mode.APPLY)).getState()).isEqualTo(State.SKIPPED);

        controller.owner = "local";
        controller.drafts.get("queries").add(record("{\"query_id\":1}"));
        assertThat(await(service.start(TENANT, DEVICE, Mode.APPLY)).getState()).isEqualTo(State.SKIPPED);

        assertThat(controller.writes).isZero();
        assertThat(controller.applies).isZero();
    }

    @Test
    void firmwareThatDoesNotReportItsChannelsIsProvisionedOnlyForTheBoardItMustBe() throws Exception {
        controller.io = null;
        assertThat(await(service.start(TENANT, DEVICE, Mode.DRAFT)).getAdded()).isEqualTo(24);

        controller.points.clear();
        controller.policies.clear();
        controller.profile = 2;
        ProvisionJob unknown = await(service.start(TENANT, DEVICE, Mode.DRAFT));
        assertThat(unknown.getState()).isEqualTo(State.FAILED);
        assertThat(controller.points).isEmpty();

        controller.io = "{\"di\":100000,\"do\":0,\"ai\":0,\"ao\":0}";
        assertThat(await(service.start(TENANT, DEVICE, Mode.DRAFT)).getState()).isEqualTo(State.FAILED);
        assertThat(controller.points).isEmpty();
    }

    @Test
    void aRefusedRecordFailsTheJobWithTheControllersReasonAndTheCountSoFar() throws Exception {
        controller.refusePointId = 3;

        ProvisionJob job = await(service.start(TENANT, DEVICE, Mode.APPLY));

        assertThat(job.getState()).isEqualTo(State.FAILED);
        assertThat(job.getMessage()).contains("HTTP 400", "bad_field", "keeps the 2 channels");
        assertThat(controller.applies).isZero();
    }

    @Test
    void oneJobPerControllerAndOnlyItsTenantSeesIt() throws Exception {
        controller.gate = new CountDownLatch(1);
        ProvisionJob job = service.start(TENANT, DEVICE, Mode.DRAFT);

        assertThatThrownBy(() -> service.start(TENANT, DEVICE, Mode.DRAFT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.getActiveJob(DEVICE, TENANT)).isSameAs(job);
        assertThat(service.getJob(job.getId(), TenantId.fromUUID(UUID.randomUUID()))).isNull();

        controller.gate.countDown();
        await(job);
        assertThat(service.getActiveJob(DEVICE, TENANT)).isNull();
        assertThat(service.getJob(job.getId(), TENANT)).isSameAs(job);
    }

    /** Until the job has finished and released the controller, which it does just after finishing. */
    private ProvisionJob await(ProvisionJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while ((job.getState() == State.RUNNING || service.getActiveJob(DEVICE, TENANT) != null)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(job.getState()).isNotEqualTo(State.RUNNING);
        return job;
    }

    private static JsonNode record(String json) {
        return JacksonUtil.toJsonNode(json);
    }

    /** The controller's REST surface as far as provisioning uses it, with a draft that pages two records at a time. */
    private static final class FakeController {

        volatile String io = "{\"di\":8,\"do\":4,\"ai\":6,\"ao\":6}";
        volatile int profile = 1;
        volatile long icc = 0;
        volatile String owner = "local";
        volatile int refusePointId = -1;
        volatile CountDownLatch gate;

        final Map<Integer, JsonNode> points = new LinkedHashMap<>();
        final Map<Integer, JsonNode> policies = new LinkedHashMap<>();
        final Map<String, List<JsonNode>> drafts = Map.of("buses", new ArrayList<>(), "queries", new ArrayList<>(),
                "scalings", new ArrayList<>(), "peers", new ArrayList<>());
        int writes;
        int applies;

        synchronized ControllerResponse handle(String method, String path, String body) throws Exception {
            if (gate != null) {
                gate.await(10, TimeUnit.SECONDS);
            }
            if (path.equals("/api/v1/info")) {
                return ok("{\"profile\":" + profile + ",\"icc\":" + icc + (io == null ? "" : ",\"io\":" + io) + "}");
            }
            if (path.equals("/api/v1/config/owner")) {
                return ok("{\"owner\":\"" + owner + "\"}");
            }
            if (path.startsWith("/api/v1/config/draft?section=")) {
                String query = path.substring(path.indexOf('=') + 1);
                String section = query.substring(0, query.indexOf('&'));
                int offset = Integer.parseInt(query.substring(query.lastIndexOf('=') + 1));
                List<JsonNode> all = switch (section) {
                    case "points" -> new ArrayList<>(points.values());
                    case "policies" -> new ArrayList<>(policies.values());
                    default -> drafts.get(section);
                };
                ObjectNode page = JacksonUtil.newObjectNode();
                ArrayNode items = page.putArray(section);
                all.stream().skip(offset).limit(2).forEach(items::add);
                page.put("offset", offset);
                page.put("truncated", offset + items.size() < all.size());
                page.put("total", all.size());
                return ok(page.toString());
            }
            if (method.equals("PUT") && path.equals("/api/v1/config/draft/points")) {
                JsonNode point = JacksonUtil.toJsonNode(body);
                if (point.get("point_id").asInt() == refusePointId) {
                    return new ControllerResponse(400, "{\"error\":\"bad_field\",\"field\":\"name\"}");
                }
                writes++;
                points.put(point.get("point_id").asInt(), point);
                return ok("");
            }
            if (method.equals("PUT") && path.equals("/api/v1/config/draft/mqtt-policies")) {
                JsonNode policy = JacksonUtil.toJsonNode(body);
                writes++;
                policies.put(policy.get("point_id").asInt(), policy);
                return ok("");
            }
            if (method.equals("POST") && path.equals("/api/v1/config/apply")) {
                applies++;
                icc++;
                return ok("{\"iccVersion\":" + icc + ",\"activation\":\"hot-swap\"}");
            }
            return new ControllerResponse(404, "");
        }

        private static ControllerResponse ok(String body) {
            return new ControllerResponse(200, body);
        }
    }

}
