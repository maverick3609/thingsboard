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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.service.inferrix.InferrixControllerClient.ControllerResponse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InferrixUploadServiceTest {

    private static final TenantId TENANT = TenantId.fromUUID(UUID.randomUUID());
    private static final TenantId OTHER_TENANT = TenantId.fromUUID(UUID.randomUUID());
    private static final DeviceId DEVICE = new DeviceId(UUID.randomUUID());

    @Mock
    private InferrixControllerAccess controllerAccess;

    private InferrixUploadService service;
    private InferrixControllerAccess.Credentials credentials;
    private InferrixControllerClient.ChunkSession session;

    /** Every chunk the fake device received, in order. */
    private final List<byte[]> chunks = new ArrayList<>();
    private final List<String> jsonCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        service = new InferrixUploadService(controllerAccess);
        credentials = new InferrixControllerAccess.Credentials("10.0.0.5", "aa", "token", null);
        when(controllerAccess.openVerifiedCredentials(any(), any(), anyString()))
                .thenReturn(credentials);
        when(controllerAccess.callWith(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    jsonCalls.add(invocation.getArgument(2) + " " + invocation.getArgument(3));
                    return response(200, "{\"state\":\"pending\",\"activation\":\"reboot\"}");
                });
        // The chunk loop streams over one held connection (firmware 0.1.15 keep-alive), so the fake
        // device is the session, not callBinary.
        session = mock(InferrixControllerClient.ChunkSession.class);
        when(controllerAccess.openChunkSession(any(), anyString())).thenReturn(session);
        when(session.post(any())).thenAnswer(invocation -> {
            chunks.add(invocation.getArgument(0));
            return response(200, "{\"state\":\"downloading\"}");
        });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void anImageIsChunkedInOrderAndAppliedWithItsOwnDigest() throws Exception {
        // A firmware image rather than a logic program: the chunk loop is the same for both, and a
        // logic program would first have to pass the ILB verifier, which has tests of its own.
        byte[] artifact = firmwareImage(0, 1, 16, 0);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, artifact);
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.DONE);
        assertThat(job.getSent()).isEqualTo(artifact.length);
        assertThat(job.getActivation()).isEqualTo("reboot");

        // Reassembled in receive order, the chunks must be the file — a wrong offset or a reused
        // buffer would still report the right byte count while flashing something else.
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        chunks.forEach(received::writeBytes);
        assertThat(received.toByteArray()).isEqualTo(artifact);

        int limit = InferrixControllerClient.maxChunkBytes("/api/v1/firmware");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length).isBetween(1, limit));
        assertThat(jsonCalls).containsExactly(
                "/api/v1/info null",
                "/api/v1/firmware/status null",
                "/api/v1/firmware/begin {\"size\":" + artifact.length + "}",
                "/api/v1/firmware/apply {\"sha256\":\"" + InferrixUploadService.sha256(artifact) + "\"}");
    }

    @Test
    void anUploadLeftUnfinishedOnTheControllerIsDiscardedBeforeBegin() throws Exception {
        // begin erases nothing and picks its slot by image header, so a half-written newer header
        // would aim this upload at the slot of the firmware the controller is running.
        firmwareUploadStates("downloading", "idle");
        byte[] image = firmwareImage(0, 1, 16, 0);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, image);
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.DONE);
        assertThat(jsonCalls).containsExactly(
                "/api/v1/info null",
                "/api/v1/firmware/status null",
                "/api/v1/firmware/apply {\"sha256\":\"" + "0".repeat(64) + "\"}",
                "/api/v1/firmware/status null",
                "/api/v1/firmware/begin {\"size\":" + image.length + "}",
                "/api/v1/firmware/apply {\"sha256\":\"" + InferrixUploadService.sha256(image) + "\"}");
    }

    @Test
    void anUnfinishedUploadTheControllerKeepsStopsTheJobBeforeBegin() throws Exception {
        firmwareUploadStates("downloading");

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("Restart the controller");
        assertThat(jsonCalls).noneMatch(call -> call.startsWith("/api/v1/firmware/begin"));
        assertThat(chunks).isEmpty();
    }

    /** The upload state the controller reports on each status read, the last one repeating. */
    private void firmwareUploadStates(String... states) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        when(controllerAccess.callWith(any(), eq("GET"), eq("/api/v1/firmware/status"), any()))
                .thenAnswer(invocation -> {
                    jsonCalls.add(invocation.getArgument(2) + " " + invocation.getArgument(3));
                    String state = states[Math.min(reads.getAndIncrement(), states.length - 1)];
                    return response(200, "{\"state\":\"" + state + "\",\"attempts\":0,\"size\":0}");
                });
    }

    @Test
    void anOversizedArtifactIsRefusedBeforeAnythingIsSent() {
        byte[] tooBig = new byte[InferrixUploadService.Kind.LOGIC.getMaxBytes() + 1];

        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.LOGIC, tooBig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over the");
        assertThat(chunks).isEmpty();
        assertThat(jsonCalls).isEmpty();
    }

    @Test
    void anEmptyArtifactIsRefused() {
        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.LOGIC, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecondUploadToTheSameControllerIsRefusedWhileTheFirstRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(session.post(any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return response(200, "{}");
        });

        InferrixUploadService.UploadJob first = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        try {
            // The device takes one uploader; a second begin would restart the upload the first is
            // writing.
            assertThatThrownBy(() -> service.start(TENANT, DEVICE,
                    InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");
        } finally {
            release.countDown();
        }
        awaitFinished(first);
    }

    @Test
    void theSameControllerCanBeUploadedAgainOnceTheFirstRunFinishes() throws Exception {
        awaitFinished(service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0)));

        InferrixUploadService.UploadJob second = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        awaitFinished(second);
        assertThat(second.getState()).isEqualTo(InferrixUploadService.State.DONE);
    }

    @Test
    void aRejectedChunkFailsTheJobCarryingTheDevicesOwnErrorName() throws Exception {
        // Built before the when(...): a mock created inside another stubbing call leaves Mockito
        // with an unfinished stub.
        ControllerResponse rejected = response(400, "{\"error\":\"chunk_rejected\"}");
        when(session.post(any())).thenReturn(rejected);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("chunk_rejected");
        // Nothing was applied, so nothing can activate.
        assertThat(jsonCalls).noneMatch(call -> call.startsWith("/api/v1/firmware/apply"));
    }

    @Test
    void aNonJsonErrorBodyStillFailsTheJobRatherThanThrowingSomethingElse() throws Exception {
        // Built before the when(...): a mock created inside another stubbing call leaves Mockito
        // with an unfinished stub.
        ControllerResponse gatewayError = response(500, "<html>gateway error</html>");
        when(session.post(any())).thenReturn(gatewayError);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("500");
    }

    @Test
    void aRunningJobIsFindableByItsControllerAndReleasedWhenItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(session.post(any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return response(200, "{}");
        });

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        try {
            // A reloaded page has only the device id to go on, so this is the whole route back to a
            // job in flight.
            assertThat(service.getActiveJob(DEVICE, TENANT)).isSameAs(job);
            assertThat(service.getActiveJob(DEVICE, OTHER_TENANT)).isNull();
        } finally {
            release.countDown();
        }
        awaitFinished(job);

        assertThat(service.getActiveJob(DEVICE, TENANT)).isNull();
        // The job itself is still pollable by id after it ends; only the device slot is released.
        assertThat(service.getJob(job.getId(), TENANT)).isSameAs(job);
    }

    @Test
    void aControllerWithNoUploadHasNoActiveJob() {
        assertThat(service.getActiveJob(DEVICE, TENANT)).isNull();
    }

    @Test
    void aJobIsInvisibleToAnotherTenant() throws Exception {
        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact(2000));
        awaitFinished(job);

        assertThat(service.getJob(job.getId(), TENANT)).isSameAs(job);
        // A job id must not confirm that an upload to someone else's controller exists.
        assertThat(service.getJob(job.getId(), OTHER_TENANT)).isNull();
        assertThat(service.getJob("not-a-job", TENANT)).isNull();
    }

    @Test
    void aFailureToOpenCredentialsFailsTheJobWithoutSendingAnything() throws Exception {
        when(controllerAccess.openVerifiedCredentials(any(), any(), anyString()))
                .thenThrow(new IllegalStateException("This device has not been adopted"));

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 16, 0));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("not been adopted");
        assertThat(chunks).isEmpty();
    }

    @Test
    void aFileWithoutAnMcubootHeaderIsRefusedBeforeAnythingIsSent() {
        // zephyr.bin instead of zephyr.signed.bin is the likely mistake, and the device cannot catch
        // it: the stream overwrites the slot and apply checks a digest the platform computed itself.
        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, artifact(2000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zephyr.signed.bin");
        assertThat(jsonCalls).isEmpty();
        assertThat(chunks).isEmpty();
    }

    @Test
    void anUnsignedImageIsRefused() {
        byte[] image = firmwareImage(0, 1, 16, 0);
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putShort(IMAGE_HEADER + IMAGE_BODY, (short) 0);

        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, image))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not signed");
    }

    @Test
    void anImageWithoutARamLoadAddressIsRefused() {
        // OTA.md: MCUboot erases such an image rather than booting it.
        byte[] image = firmwareImage(0, 1, 16, 0);
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0);

        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, image))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RAM-load");
    }

    @Test
    void aTruncatedImageIsRefused() {
        byte[] image = java.util.Arrays.copyOf(firmwareImage(0, 1, 16, 0), IMAGE_HEADER + 100);

        assertThatThrownBy(() -> service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, image))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void anImageThatDoesNotOutrankTheRunningFirmwareFailsBeforeBegin() throws Exception {
        runningFirmware("0.1.15+0");

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, firmwareImage(0, 1, 15, 7));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("0.1.15+7").contains("does not outrank").contains("0.1.15+0");
        assertThat(jsonCalls).noneMatch(call -> call.startsWith("/api/v1/firmware/begin"));
        assertThat(chunks).isEmpty();
    }

    @Test
    void aNewerImageIsWrittenWhole() throws Exception {
        runningFirmware("0.1.15+0");
        byte[] image = firmwareImage(0, 1, 16, 0);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE, InferrixUploadService.Kind.FIRMWARE, image);
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.DONE);
        assertThat(job.getImageVersion()).isEqualTo("0.1.16+0");
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        chunks.forEach(received::writeBytes);
        assertThat(received.toByteArray()).isEqualTo(image);
    }

    @Test
    void theVersionRuleIgnoresTheBuildNumberTheWayMcubootDoes() {
        assertThat(new InferrixUploadService.FirmwareImage(0, 1, 15, 9).outranks("0.1.15+0")).isFalse();
        assertThat(new InferrixUploadService.FirmwareImage(0, 1, 15, 0).outranks("0.1.16+0")).isFalse();
        assertThat(new InferrixUploadService.FirmwareImage(0, 1, 16, 0).outranks("0.1.15+9")).isTrue();
        assertThat(new InferrixUploadService.FirmwareImage(0, 2, 0, 0).outranks("0.1.99+0")).isTrue();
        assertThat(new InferrixUploadService.FirmwareImage(1, 0, 0, 0).outranks("0.9.9")).isTrue();
        // Firmware too old to report a version cannot be held against the image.
        assertThat(new InferrixUploadService.FirmwareImage(0, 1, 15, 0).outranks("0")).isTrue();
        assertThat(new InferrixUploadService.FirmwareImage(0, 1, 15, 0).outranks(null)).isTrue();
    }

    private void runningFirmware(String version) throws Exception {
        ControllerResponse info = response(200, "{\"fw\":\"" + version + "\",\"profile\":1}");
        when(controllerAccess.callWith(any(), eq("GET"), eq("/api/v1/info"), any())).thenReturn(info);
    }

    private static final int IMAGE_HEADER = 0x200;
    private static final int IMAGE_BODY = 3000;

    /**
     * The shape `imgtool sign --pad-header --load-addr` produces: a padded header, the body, then
     * the unprotected TLV area that carries the signature.
     */
    private static byte[] firmwareImage(int major, int minor, int revision, int build) {
        byte[] image = new byte[IMAGE_HEADER + IMAGE_BODY + 8];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 0x96f3b83d);
        buffer.putInt(4, 0x24000000);
        buffer.putShort(8, (short) IMAGE_HEADER);
        buffer.putInt(12, IMAGE_BODY);
        buffer.putInt(16, 0x20);
        buffer.put(20, (byte) major);
        buffer.put(21, (byte) minor);
        buffer.putShort(22, (short) revision);
        buffer.putInt(24, build);
        for (int i = 0; i < IMAGE_BODY; i++) {
            image[IMAGE_HEADER + i] = (byte) (i * 13 + 5);
        }
        buffer.putShort(IMAGE_HEADER + IMAGE_BODY, (short) 0x6907);
        buffer.putShort(IMAGE_HEADER + IMAGE_BODY + 2, (short) 8);
        return image;
    }

    @Test
    void everyChunkFitsTheControllersRequestCap() {
        for (InferrixUploadService.Kind kind : InferrixUploadService.Kind.values()) {
            int limit = InferrixControllerClient.maxChunkBytes(kind.getPath());
            assertThat(limit).withFailMessage("no room for a %s chunk", kind).isGreaterThan(0);
        }
    }

    private void awaitFinished(InferrixUploadService.UploadJob job) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (job.getState() != InferrixUploadService.State.RUNNING) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The upload never finished; state is " + job.getState());
    }

    /** Distinctive bytes, so a misplaced chunk shows up as a content mismatch rather than a size one. */
    private static byte[] artifact(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static ControllerResponse response(int status, String body) {
        return new ControllerResponse(status, body);
    }
}
