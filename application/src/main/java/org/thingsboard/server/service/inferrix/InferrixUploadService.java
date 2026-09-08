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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.util.TbCoreComponent;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams a firmware image or a logic program to a controller.
 *
 * <p><b>Why this is not the REST proxy.</b> Everything else the platform sends a controller is one
 * JSON request; these are octet streams that must be cut into pieces small enough for the device's
 * 2048-byte cap on a whole request, which for a 500 KB image is several hundred of them. The
 * firmware closes the connection after every response, so each chunk is its own TLS handshake
 * against a microcontroller — the run takes minutes, and driving it from a browser would mean the
 * same hundreds of round trips with the platform added to each one. The loop belongs here.
 *
 * <p><b>Why it is a job rather than a request.</b> A minutes-long synchronous request holds a
 * request thread for its whole duration, tells the operator nothing while it runs, and dies to any
 * proxy timeout in front of the platform. The upload is handed to a worker and polled instead.
 *
 * <p>Jobs live in memory: a platform restart mid-upload leaves the device with a half-written
 * staging slot, which is safe — the slot is not activated until {@code apply} verifies the whole
 * image by SHA-256, and the next {@code begin} erases it.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixUploadService {

    /**
     * The device takes one uploader at a time and a run is mostly waiting on a microcontroller, so
     * the pool is small on purpose — it bounds how many controllers are being written at once, not
     * how fast any one of them goes.
     */
    private static final int MAX_CONCURRENT_UPLOADS = 4;

    /**
     * How many artifacts may be waiting for a worker. Each one is held in memory for its whole
     * life, so this is a heap bound as much as a fairness one — an unbounded queue would let a
     * fleet-wide rollout hold every image at once.
     */
    private static final int MAX_QUEUED_UPLOADS = 8;

    private static final int MAX_TRACKED_JOBS = 256;
    private static final Duration JOB_RETENTION = Duration.ofMinutes(30);

    /**
     * Expiry is from last access, not from creation: an upload to a slow device can outlive any
     * fixed window, and evicting a job while it is still running would answer its own poll with
     * "no such upload".
     */
    private final Cache<String, UploadJob> jobs = Caffeine.newBuilder()
            .expireAfterAccess(JOB_RETENTION)
            .maximumSize(MAX_TRACKED_JOBS)
            .build();

    /**
     * One upload per device. The firmware is explicit that it accepts a single uploader, and a
     * second {@code begin} would erase the slot the first is still writing into.
     */
    private final ConcurrentMap<DeviceId, String> activeByDevice = new ConcurrentHashMap<>();

    private final ExecutorService executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_UPLOADS, MAX_CONCURRENT_UPLOADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_UPLOADS),
            ThingsBoardThreadFactory.forName("inferrix-upload"));

    private final InferrixControllerAccess controllerAccess;

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** What is being written, and the limits that go with it. */
    public enum Kind {

        FIRMWARE("/api/v1/firmware", 1024 * 1024),
        LOGIC("/api/v1/logic", 128 * 1024);

        @Getter
        private final String path;
        /**
         * Refused above this size. The artifact is held in memory for the whole run, and neither a
         * signed MCUboot image (~500 KB) nor an ILB program (~64 KB) comes close.
         */
        @Getter
        private final int maxBytes;

        Kind(String path, int maxBytes) {
            this.path = path;
            this.maxBytes = maxBytes;
        }

        public String beginPath() {
            return path + "/begin";
        }

        public String applyPath() {
            return path + "/apply";
        }

        public String statusPath() {
            return path + "/status";
        }
    }

    public enum State { RUNNING, DONE, FAILED }

    /**
     * Progress of one upload.
     *
     * <p>{@code tenantId} is carried so a poll can be refused for anyone else's job: a job id is a
     * bearer of information about another tenant's device otherwise.
     */
    @Getter
    public static final class UploadJob {

        private final String id = UUID.randomUUID().toString();
        private final TenantId tenantId;
        private final DeviceId deviceId;
        private final Kind kind;
        private final int totalBytes;
        private final long startedTs = System.currentTimeMillis();

        private final AtomicInteger sentBytes = new AtomicInteger();
        private volatile State state = State.RUNNING;
        private volatile String message;
        /** From the device's apply response: "reboot" for both kinds today. */
        private volatile String activation;

        UploadJob(TenantId tenantId, DeviceId deviceId, Kind kind, int totalBytes) {
            this.tenantId = tenantId;
            this.deviceId = deviceId;
            this.kind = kind;
            this.totalBytes = totalBytes;
        }

        public int getSent() {
            return sentBytes.get();
        }

        public boolean isVisibleTo(TenantId candidate) {
            return tenantId.equals(candidate);
        }
    }

    /**
     * Accepts an artifact and starts writing it to the device.
     *
     * @return the job to poll; never null
     */
    public UploadJob start(TenantId tenantId, DeviceId deviceId, Kind kind, byte[] artifact) {
        if (artifact == null || artifact.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (artifact.length > kind.getMaxBytes()) {
            throw new IllegalArgumentException("The uploaded file is " + artifact.length
                    + " bytes, over the " + kind.getMaxBytes() + "-byte limit for a "
                    + kind.name().toLowerCase() + " upload");
        }
        UploadJob job = new UploadJob(tenantId, deviceId, kind, artifact.length);
        // computeIfAbsent, not a check-then-put: two operators pressing upload at the same moment
        // would otherwise both pass the check and the second begin would erase the first's slot.
        String running = activeByDevice.computeIfAbsent(deviceId, id -> job.getId());
        if (!running.equals(job.getId())) {
            throw new IllegalStateException("An upload to this controller is already running");
        }
        jobs.put(job.getId(), job);
        try {
            executor.submit(() -> run(job, artifact));
        } catch (RejectedExecutionException e) {
            activeByDevice.remove(deviceId, job.getId());
            fail(job, "The platform is already holding as many controller uploads as it will take;"
                    + " try again shortly");
        }
        return job;
    }

    public UploadJob getJob(String jobId, TenantId tenantId) {
        UploadJob job = jobs.getIfPresent(jobId);
        return job != null && job.isVisibleTo(tenantId) ? job : null;
    }

    /**
     * The upload currently running against one controller, if any.
     *
     * <p>Without this a reloaded page has no way back to a job in flight: the id lived only in the
     * browser, so the operator would see a device that refuses a new upload and no sign of the one
     * already writing to it.
     */
    public UploadJob getActiveJob(DeviceId deviceId, TenantId tenantId) {
        String jobId = activeByDevice.get(deviceId);
        return jobId == null ? null : getJob(jobId, tenantId);
    }

    private void run(UploadJob job, byte[] artifact) {
        try {
            InferrixControllerAccess.Credentials credentials = controllerAccess
                    .openVerifiedCredentials(job.getTenantId(), job.getDeviceId(),
                            job.getKind().statusPath());

            requireOk(controllerAccess.callWith(credentials, "POST", job.getKind().beginPath(),
                    "{\"size\":" + artifact.length + "}"), "begin");

            int chunkSize = InferrixControllerClient.maxChunkBytes(job.getKind().getPath());
            if (chunkSize <= 0) {
                throw new IOException("No room for a chunk body within the controller's request cap");
            }
            for (int offset = 0; offset < artifact.length; offset += chunkSize) {
                int length = Math.min(chunkSize, artifact.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(artifact, offset, chunk, 0, length);
                requireOk(controllerAccess.callBinary(credentials, job.getKind().getPath(), chunk),
                        "chunk at offset " + offset);
                job.sentBytes.addAndGet(length);
            }

            // The digest is taken over what the platform actually holds, so apply verifies the whole
            // path — operator's browser to platform to device — rather than only the device's flash.
            HttpResponse<String> applied = controllerAccess.callWith(credentials, "POST",
                    job.getKind().applyPath(), "{\"sha256\":\"" + sha256(artifact) + "\"}");
            requireOk(applied, "apply");
            job.activation = textField(applied.body(), "activation");
            job.message = textField(applied.body(), "state");
            job.state = State.DONE;
            log.info("[{}] Inferrix {} upload finished: {} bytes, activation {}", job.getDeviceId(),
                    job.getKind(), job.getTotalBytes(), job.getActivation());
        } catch (Exception e) {
            log.warn("[{}] Inferrix {} upload failed", job.getDeviceId(), job.getKind(), e);
            fail(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            activeByDevice.remove(job.getDeviceId(), job.getId());
        }
    }

    /**
     * The device's own error name is the only thing that says what went wrong — {@code
     * chunk_rejected} means the slot overran or flash failed, {@code verify_failed} means the image
     * did not survive the trip — so it is carried through rather than flattened to a status code.
     */
    private void requireOk(HttpResponse<String> response, String step) throws IOException {
        if (response.statusCode() == 200) {
            return;
        }
        String error = textField(response.body(), "error");
        throw new IOException("The controller rejected the " + step + " step with HTTP "
                + response.statusCode() + (error == null ? "" : " (" + error + ")"));
    }

    private void fail(UploadJob job, String message) {
        job.message = message;
        job.state = State.FAILED;
    }

    /**
     * One field of a device response, or null.
     *
     * <p>Swallows a parse failure on purpose: this is called while reporting an error, and
     * {@code JacksonUtil.toJsonNode} throws rather than returning null on a body that is not JSON.
     * Losing the device's error name is acceptable; replacing the real failure with a parse
     * exception is not.
     */
    private static String textField(String body, String field) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JacksonUtil.toJsonNode(body);
            return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String sha256(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

}
