// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.ControllerResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives a controller a point and a publish policy for each of its own inputs and outputs, so its
 * local I/O reaches the platform without anyone entering four dozen records by hand.
 *
 * <p>Two ways in. At adoption, a controller that has never been configured ({@code icc} 0) is
 * provisioned and the result applied, so it starts publishing at once. From the Configuration tab,
 * the same records go into the draft for the operator to review and apply, and only channels that
 * have no point yet get one.
 *
 * <p><b>Why a job.</b> A board with 24 channels takes about fifty REST calls. The firmware serves two
 * clients at a time, so they go one after another, and each is its own TLS handshake against a
 * microcontroller, because keep-alive covers only the upload routes. That is tens of seconds.
 *
 * <p>The analog points are left unscaled, so they report raw counts: the transfer from counts to
 * volts or milliamps depends on the analog front end, and those constants do not exist yet
 * (firmware notes §31.1). Outputs are not writable: a program may own them, and opening one to
 * remote writes is a decision for the operator.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixProvisionService {

    private static final int MAX_CONCURRENT_JOBS = 4;
    private static final int MAX_QUEUED_JOBS = 64;
    private static final int MAX_TRACKED_JOBS = 256;
    private static final Duration JOB_RETENTION = Duration.ofMinutes(30);
    private static final int MAX_SECTION_PAGES = 256;

    /**
     * What a board may claim per kind of channel. The device's own figure decides how many records
     * are written, so an absurd one is refused rather than turned into a thousand points.
     */
    private static final int MAX_CHANNELS_PER_KIND = 64;

    /** DI, DO, AI and AO: the firmware's source classes 0 to 3, in the order they are numbered. */
    private static final String[] KIND_KEYS = {"di", "do", "ai", "ao"};
    private static final String[] KIND_NAMES = {"DI", "DO", "AI", "AO"};

    /** The only board firmware older than 0.1.16 runs on, which does not report its channels. */
    private static final int PROFILE_IO_8DI4DO6AI6AO = 1;
    private static final int[] PROFILE_1_COUNTS = {8, 4, 6, 6};

    /** The draft sections besides the two this service writes, which it reads anyway. */
    private static final String[] OTHER_DRAFT_SECTIONS = {"buses", "queries", "scalings", "peers"};

    private static final int FORMAT_U16 = 0;
    private static final int FORMAT_BIT = 6;
    private static final int NO_SCALING = 65535;
    private static final int TRIGGER_INTERVAL = 1;
    private static final int TRIGGER_ON_CHANGE = 2;
    /** About 1 % of the ADC's 0-4095 span, in counts while the inputs are unscaled. */
    private static final float AI_DEADBAND_COUNTS = 40f;

    private final Cache<String, ProvisionJob> jobs = Caffeine.newBuilder()
            .expireAfterAccess(JOB_RETENTION)
            .maximumSize(MAX_TRACKED_JOBS)
            .build();

    /** One job per device: two would race each other for the same free point ids. */
    private final ConcurrentMap<DeviceId, String> activeByDevice = new ConcurrentHashMap<>();

    private final ExecutorService executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_JOBS, MAX_CONCURRENT_JOBS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
            ThingsBoardThreadFactory.forName("inferrix-provision"));

    private final InferrixControllerAccess controllerAccess;

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public enum Mode {
        /** Adds the records to the draft and stops there, for the operator to review. */
        DRAFT,
        /**
         * Adoption's: provisions and applies, but only a controller that has never been configured
         * and whose draft is empty, because applying would otherwise take someone else's edits live.
         */
        APPLY
    }

    public enum State { RUNNING, DONE, SKIPPED, FAILED }

    @Getter
    public static final class ProvisionJob {

        private final String id = UUID.randomUUID().toString();
        private final TenantId tenantId;
        private final DeviceId deviceId;
        private final Mode mode;

        /** Channels given a point and a policy so far, out of {@link #total}. */
        private final AtomicInteger addedCount = new AtomicInteger();
        private volatile int total;
        private volatile State state = State.RUNNING;
        private volatile String message;
        /** APPLY only: the config version and activation the controller answered the apply with. */
        private volatile Long iccVersion;
        private volatile String activation;

        ProvisionJob(TenantId tenantId, DeviceId deviceId, Mode mode) {
            this.tenantId = tenantId;
            this.deviceId = deviceId;
            this.mode = mode;
        }

        public int getAdded() {
            return addedCount.get();
        }

        public boolean isVisibleTo(TenantId candidate) {
            return tenantId.equals(candidate);
        }
    }

    public ProvisionJob start(TenantId tenantId, DeviceId deviceId, Mode mode) {
        ProvisionJob job = new ProvisionJob(tenantId, deviceId, mode);
        String running = activeByDevice.computeIfAbsent(deviceId, id -> job.getId());
        if (!running.equals(job.getId())) {
            throw new IllegalStateException("This controller's local I/O is already being provisioned");
        }
        jobs.put(job.getId(), job);
        try {
            executor.submit(() -> run(job));
        } catch (RejectedExecutionException e) {
            activeByDevice.remove(deviceId, job.getId());
            finish(job, State.FAILED, "The platform is already provisioning as many controllers as it will"
                    + " take; try again shortly");
        }
        return job;
    }

    public ProvisionJob getJob(String jobId, TenantId tenantId) {
        ProvisionJob job = jobs.getIfPresent(jobId);
        return job != null && job.isVisibleTo(tenantId) ? job : null;
    }

    public ProvisionJob getActiveJob(DeviceId deviceId, TenantId tenantId) {
        String jobId = activeByDevice.get(deviceId);
        return jobId == null ? null : getJob(jobId, tenantId);
    }

    private void run(ProvisionJob job) {
        try {
            JsonNode info = readJson(job, "/api/v1/info", "read of the controller's identity");
            if (job.getMode() == Mode.APPLY && info.path("icc").asLong(-1) != 0) {
                finish(job, State.SKIPPED, "The controller already runs a configuration");
                return;
            }
            int[] counts = channelCounts(info);

            JsonNode owner = readJson(job, "/api/v1/config/owner", "read of the configuration owner");
            if ("platform".equals(owner.path("owner").asText())) {
                finish(job, State.SKIPPED, "A manifest owns this controller's configuration, so its draft"
                        + " cannot be edited; take ownership on the Configuration tab first");
                return;
            }

            List<JsonNode> points = readDraftSection(job, "points");
            List<JsonNode> policies = readDraftSection(job, "policies");
            if (job.getMode() == Mode.APPLY && (!points.isEmpty() || !policies.isEmpty() || hasOtherDraftRecords(job))) {
                finish(job, State.SKIPPED, "The controller's draft holds unapplied changes, so nothing was"
                        + " added or applied");
                return;
            }

            Set<Integer> takenIds = new HashSet<>();
            Set<Channel> covered = new HashSet<>();
            points.forEach(point -> {
                takenIds.add(point.path("point_id").asInt());
                int source = point.path("source").asInt(-1);
                if (source >= 0 && source < KIND_KEYS.length) {
                    covered.add(new Channel(source, point.path("source_ref").asInt()));
                }
            });
            policies.forEach(policy -> takenIds.add(policy.path("point_id").asInt()));

            List<Provisioned> plan = plan(counts, covered, takenIds);
            job.total = plan.size();
            for (Provisioned record : plan) {
                int id = record.point().get("point_id").asInt();
                requireOk(call(job, "PUT", "/api/v1/config/draft/points", record.point()), "write of point " + id);
                requireOk(call(job, "PUT", "/api/v1/config/draft/mqtt-policies", record.policy()),
                        "write of the publish policy for point " + id);
                job.addedCount.incrementAndGet();
            }

            if (job.getMode() == Mode.APPLY && !plan.isEmpty()) {
                apply(job);
            }
            finish(job, State.DONE, null);
            log.info("[{}] Inferrix controller local I/O provisioned: {} channels, mode {}, config version {}",
                    job.getDeviceId(), job.getAdded(), job.getMode(), job.getIccVersion());
        } catch (Exception e) {
            log.warn("[{}] Inferrix controller local I/O provisioning failed", job.getDeviceId(), e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            finish(job, State.FAILED, job.getAdded() == 0 ? reason
                    : reason + ". The draft keeps the " + job.getAdded() + " channels already added.");
        } finally {
            activeByDevice.remove(job.getDeviceId(), job.getId());
        }
    }

    /**
     * Applies the draft, then checks the controller now reports the version it answered with. Only a
     * confirmed hot swap can be checked: a pending one lands at the next scan, and a reboot
     * activation only at the next restart.
     */
    private void apply(ProvisionJob job) throws Exception {
        ControllerResponse applied = call(job, "POST", "/api/v1/config/apply", null);
        requireOk(applied, "apply");
        JsonNode result = parse(applied.body());
        job.iccVersion = result.hasNonNull("iccVersion") ? result.get("iccVersion").asLong() : null;
        job.activation = result.path("activation").asText(null);
        if ("hot-swap".equals(job.activation) && job.iccVersion != null) {
            long running = readJson(job, "/api/v1/info", "read-back after the apply").path("icc").asLong(-1);
            if (running != job.iccVersion) {
                throw new IOException("The controller applied config version " + job.iccVersion
                        + " but reports running version " + running);
            }
        }
    }

    private boolean hasOtherDraftRecords(ProvisionJob job) throws Exception {
        for (String section : OTHER_DRAFT_SECTIONS) {
            if (!readDraftSection(job, section).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many channels of each kind the board has: the {@code io} object firmware 0.1.16 reports,
     * or, for older firmware on the one board that exists, that board's complement.
     */
    private static int[] channelCounts(JsonNode info) throws IOException {
        JsonNode io = info.get("io");
        if (io == null || !io.isObject()) {
            if (info.path("profile").asInt(-1) == PROFILE_IO_8DI4DO6AI6AO) {
                return PROFILE_1_COUNTS.clone();
            }
            throw new IOException("The controller does not report its inputs and outputs, and its hardware"
                    + " profile is not one the platform knows");
        }
        int[] counts = new int[KIND_KEYS.length];
        for (int i = 0; i < KIND_KEYS.length; i++) {
            JsonNode count = io.get(KIND_KEYS[i]);
            if (count == null || !count.canConvertToInt() || count.asInt() < 0
                    || count.asInt() > MAX_CHANNELS_PER_KIND) {
                throw new IOException("The controller reports an unusable " + KIND_NAMES[i] + " count: " + count);
            }
            counts[i] = count.asInt();
        }
        return counts;
    }

    /**
     * The records for every channel without a point, numbered the way the firmware's own examples
     * number a board: DI1 is 1, DO1 follows the last DI, and so on. A preferred id already in use
     * gives way to the next free one above it.
     */
    private static List<Provisioned> plan(int[] counts, Set<Channel> covered, Set<Integer> takenIds) {
        Set<Integer> taken = new HashSet<>(takenIds);
        List<Provisioned> plan = new ArrayList<>();
        int firstId = 1;
        for (int source = 0; source < counts.length; source++) {
            for (int channel = 0; channel < counts[source]; channel++) {
                if (covered.contains(new Channel(source, channel))) {
                    continue;
                }
                int id = firstId + channel;
                // Cannot run past 65535: a draft holds at most 1024 points and 1024 policies.
                while (taken.contains(id)) {
                    id++;
                }
                taken.add(id);
                plan.add(new Provisioned(point(id, source, channel), policy(id, source)));
            }
            firstId += counts[source];
        }
        return plan;
    }

    private static ObjectNode point(int id, int source, int channel) {
        ObjectNode point = JacksonUtil.newObjectNode();
        point.put("point_id", id);
        point.put("source", source);
        point.put("data_format", source <= 1 ? FORMAT_BIT : FORMAT_U16);
        point.put("source_ref", channel);
        point.put("offset", 0);
        point.put("scaling_idx", NO_SCALING);
        point.put("flags", 0);
        point.put("refresh_s", 0);
        point.put("name", KIND_NAMES[source] + (channel + 1));
        return point;
    }

    /**
     * Digital channels on every change plus a five-minute heartbeat at QoS 1, since a missed edge is
     * a missed event. Analog inputs on a change of about 1 % plus a one-minute heartbeat at QoS 0,
     * since the next sample supersedes a lost one. Analog outputs only change when something writes
     * them, so on change alone.
     */
    private static ObjectNode policy(int id, int source) {
        ObjectNode policy = JacksonUtil.newObjectNode();
        policy.put("point_id", id);
        switch (source) {
            case 0, 1 -> {
                policy.put("trigger", TRIGGER_ON_CHANGE | TRIGGER_INTERVAL);
                policy.put("qos", 1);
                policy.put("interval_s", 300);
                policy.put("deadband_bits", 0);
            }
            case 2 -> {
                policy.put("trigger", TRIGGER_ON_CHANGE | TRIGGER_INTERVAL);
                policy.put("qos", 0);
                policy.put("interval_s", 60);
                policy.put("deadband_bits", Float.floatToIntBits(AI_DEADBAND_COUNTS));
            }
            default -> {
                policy.put("trigger", TRIGGER_ON_CHANGE);
                policy.put("qos", 0);
                policy.put("interval_s", 0);
                policy.put("deadband_bits", 0);
            }
        }
        return policy;
    }

    /** One draft section whole, following the device's paging the way the browser does. */
    private List<JsonNode> readDraftSection(ProvisionJob job, String section) throws Exception {
        List<JsonNode> records = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_SECTION_PAGES; page++) {
            JsonNode node = readJson(job, "/api/v1/config/draft?section=" + section + "&offset=" + offset,
                    "read of the draft " + section);
            JsonNode items = node.get(section);
            if (items == null || !items.isArray() || items.isEmpty()) {
                break;
            }
            items.forEach(records::add);
            if (!node.path("truncated").asBoolean(false) || node.path("offset").asInt(-1) != offset) {
                break;
            }
            offset += items.size();
        }
        return records;
    }

    private JsonNode readJson(ProvisionJob job, String path, String step) throws Exception {
        ControllerResponse response = call(job, "GET", path, null);
        requireOk(response, step);
        return parse(response.body());
    }

    private ControllerResponse call(ProvisionJob job, String method, String path, JsonNode body) throws Exception {
        return controllerAccess.call(job.getTenantId(), job.getDeviceId(), method, path,
                body == null ? null : JacksonUtil.toString(body));
    }

    private static JsonNode parse(String body) throws IOException {
        try {
            JsonNode node = body == null || body.isBlank() ? null : JacksonUtil.toJsonNode(body);
            if (node == null || !node.isObject()) {
                throw new IOException("The controller answered with something other than a JSON object");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw new IOException("The controller answered with something other than JSON", e);
        }
    }

    /** The device's own error name is carried through, since it says what is wrong with the draft. */
    private static void requireOk(ControllerResponse response, String step) throws IOException {
        if (response.statusCode() == 200) {
            return;
        }
        String error = null;
        try {
            error = parse(response.body()).path("error").asText(null);
        } catch (IOException e) {
            // No body, or not JSON: the status alone will have to do.
        }
        throw new IOException("The controller rejected the " + step + " with HTTP " + response.statusCode()
                + (error == null ? "" : " (" + error + ")"));
    }

    private static void finish(ProvisionJob job, State state, String message) {
        job.message = message;
        job.state = state;
    }

    private record Channel(int source, int channel) {
    }

    private record Provisioned(ObjectNode point, ObjectNode policy) {
    }

}
