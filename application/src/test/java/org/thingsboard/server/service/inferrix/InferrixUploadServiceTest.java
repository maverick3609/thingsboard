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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        when(controllerAccess.callBinary(any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    chunks.add(invocation.getArgument(2));
                    return response(200, "{\"state\":\"downloading\"}");
                });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void anImageIsChunkedInOrderAndAppliedWithItsOwnDigest() throws Exception {
        byte[] artifact = artifact(5000);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact);
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.DONE);
        assertThat(job.getSent()).isEqualTo(artifact.length);
        assertThat(job.getActivation()).isEqualTo("reboot");

        // Reassembled in receive order, the chunks must be the file — a wrong offset or a reused
        // buffer would still report the right byte count while flashing something else.
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        chunks.forEach(received::writeBytes);
        assertThat(received.toByteArray()).isEqualTo(artifact);

        int limit = InferrixControllerClient.maxChunkBytes("/api/v1/logic");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length).isBetween(1, limit));
        assertThat(jsonCalls).containsExactly(
                "/api/v1/logic/begin {\"size\":5000}",
                "/api/v1/logic/apply {\"sha256\":\"" + InferrixUploadService.sha256(artifact) + "\"}");
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
        when(controllerAccess.callBinary(any(), anyString(), any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return response(200, "{}");
        });

        InferrixUploadService.UploadJob first = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact(4000));
        try {
            // The device takes one uploader; a second begin would erase the slot the first is
            // writing into.
            assertThatThrownBy(() -> service.start(TENANT, DEVICE,
                    InferrixUploadService.Kind.LOGIC, artifact(4000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");
        } finally {
            release.countDown();
        }
        awaitFinished(first);
    }

    @Test
    void theSameControllerCanBeUploadedAgainOnceTheFirstRunFinishes() throws Exception {
        awaitFinished(service.start(TENANT, DEVICE, InferrixUploadService.Kind.LOGIC, artifact(2000)));

        InferrixUploadService.UploadJob second = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact(2000));
        awaitFinished(second);
        assertThat(second.getState()).isEqualTo(InferrixUploadService.State.DONE);
    }

    @Test
    void aRejectedChunkFailsTheJobCarryingTheDevicesOwnErrorName() throws Exception {
        // Built before the when(...): a mock created inside another stubbing call leaves Mockito
        // with an unfinished stub.
        ControllerResponse rejected = response(400, "{\"error\":\"chunk_rejected\"}");
        when(controllerAccess.callBinary(any(), anyString(), any())).thenReturn(rejected);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact(3000));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("chunk_rejected");
        // Nothing was applied, so nothing can activate.
        assertThat(jsonCalls).noneMatch(call -> call.startsWith("/api/v1/logic/apply"));
    }

    @Test
    void aNonJsonErrorBodyStillFailsTheJobRatherThanThrowingSomethingElse() throws Exception {
        // Built before the when(...): a mock created inside another stubbing call leaves Mockito
        // with an unfinished stub.
        ControllerResponse gatewayError = response(500, "<html>gateway error</html>");
        when(controllerAccess.callBinary(any(), anyString(), any())).thenReturn(gatewayError);

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.LOGIC, artifact(3000));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("500");
    }

    @Test
    void aRunningJobIsFindableByItsControllerAndReleasedWhenItEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(controllerAccess.callBinary(any(), anyString(), any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return response(200, "{}");
        });

        InferrixUploadService.UploadJob job = service.start(TENANT, DEVICE,
                InferrixUploadService.Kind.FIRMWARE, artifact(4000));
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
                InferrixUploadService.Kind.FIRMWARE, artifact(2000));
        awaitFinished(job);

        assertThat(job.getState()).isEqualTo(InferrixUploadService.State.FAILED);
        assertThat(job.getMessage()).contains("not been adopted");
        assertThat(chunks).isEmpty();
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
