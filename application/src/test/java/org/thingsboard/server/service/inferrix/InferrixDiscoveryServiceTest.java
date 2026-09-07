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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.thingsboard.server.common.data.id.TenantId;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferrixDiscoveryServiceTest {

    private static final String UID = "aabbccddeeff001122334455";

    private InferrixDiscoveryService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new InferrixDiscoveryService();
        ReflectionTestUtils.setField(service, "bindAddress", "127.0.0.1");
        ReflectionTestUtils.setField(service, "port", 0);
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void anAnnounceIsAckedAndRecorded() throws Exception {
        String reply = announce("{\"uid\":\"" + UID + "\",\"model\":\"infx-ctrl-h750\",\"ip\":\"10.0.0.9\"}");

        // Any reply containing "ack": true is what stops the device's 5-minute announce loop.
        assertTrue(reply.contains("\"ack\""));
        assertTrue(reply.contains("true"));

        InferrixControllerSighting sighting = awaitSighting(UID);
        assertEquals("infx-ctrl-h750", sighting.getIdentity().path("model").asText());
        assertEquals(1, sighting.getAnnounceCount());
    }

    @Test
    void theConnectBackAddressIsTheTcpPeerNotTheBodysClaim() throws Exception {
        // The body is unauthenticated. Believing its "ip" would let anything on the network point
        // adoption — which hands over an ownership password — at a host of its choosing.
        announce("{\"uid\":\"" + UID + "\",\"ip\":\"203.0.113.7\"}");

        InferrixControllerSighting sighting = awaitSighting(UID);
        assertEquals("127.0.0.1", sighting.getConnectBackIp());
        assertEquals("203.0.113.7", sighting.getIdentity().path("ip").asText());
    }

    @Test
    void repeatAnnouncesFoldIntoOneSightingAndDoNotRewriteTheIdentity() throws Exception {
        announce("{\"uid\":\"" + UID + "\",\"name\":\"AHU-1\"}");
        awaitSighting(UID);
        announce("{\"uid\":\"" + UID + "\",\"name\":\"attacker relabelled me\"}");

        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> service.getSighting(UID).getAnnounceCount() == 2);
        assertEquals(1, service.getAllSightings().size());
        assertEquals("AHU-1", service.getSighting(UID).getIdentity().path("name").asText());
    }

    @Test
    void junkIsAckedButNeverRecorded() throws Exception {
        // Still acked: withholding it only makes a broken device announce forever.
        assertTrue(announce("this is not json").contains("\"ack\""));
        assertTrue(announce("{\"model\":\"no uid here\"}").contains("\"ack\""));
        assertTrue(announce("{\"uid\":\"../../etc/passwd\"}").contains("\"ack\""));
        assertTrue(announce("{\"uid\":\"\"}").contains("\"ack\""));

        Thread.sleep(300);
        assertTrue(service.getAllSightings().isEmpty());
        assertNull(service.getSighting("../../etc/passwd"));
    }

    @Test
    void adoptionClearsTheSighting() throws Exception {
        announce("{\"uid\":\"" + UID + "\"}");
        awaitSighting(UID);

        service.forget(UID);
        assertTrue(service.getAllSightings().isEmpty());
    }

    @Test
    void uidsThatCouldEscapeATopicOrAPathAreRefused() {
        assertTrue(InferrixDiscoveryService.isPlausibleUid(UID));
        assertFalse(InferrixDiscoveryService.isPlausibleUid("has/slash"));
        assertFalse(InferrixDiscoveryService.isPlausibleUid("has+plus"));
        assertFalse(InferrixDiscoveryService.isPlausibleUid("has#hash"));
        assertFalse(InferrixDiscoveryService.isPlausibleUid("has space"));
        assertFalse(InferrixDiscoveryService.isPlausibleUid(""));
        assertFalse(InferrixDiscoveryService.isPlausibleUid(null));
        assertFalse(InferrixDiscoveryService.isPlausibleUid("x".repeat(65)));
    }

    @Test
    void anAnnounceSplitAcrossTcpSegmentsIsNotTruncated() throws Exception {
        // available()==0 between segments used to end the read early, leaving half an object that
        // failed to parse and a controller that never showed up in the list.
        String head = "{\"uid\":\"" + UID + "\",\"model\":\"infx-";
        String tail = "ctrl-h750\"}";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", service.getBoundPort()), 2000);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(150);
            out.write(tail.getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.getInputStream().read(new byte[64]);
        }

        assertEquals("infx-ctrl-h750", awaitSighting(UID).getIdentity().path("model").asText());
    }

    @Test
    void aSightingIsInvisibleToEveryTenantUntilItIsAssignedToOne() throws Exception {
        // An unadopted controller belongs to nobody. Without this scoping every tenant admin on the
        // installation could enumerate every controller on the network — address, deployment name
        // and location included.
        announce("{\"uid\":\"" + UID + "\",\"location\":\"Building A roof\"}");
        awaitSighting(UID);

        TenantId mine = TenantId.fromUUID(UUID.randomUUID());
        TenantId theirs = TenantId.fromUUID(UUID.randomUUID());

        assertTrue(service.getSightingsFor(mine).isEmpty(), "unassigned must be invisible");
        assertNull(service.getSightingFor(UID, mine));
        assertEquals(1, service.getAllSightings().size(), "the sysadmin view still sees it");

        service.assign(UID, mine);
        assertEquals(1, service.getSightingsFor(mine).size());
        assertNotNull(service.getSightingFor(UID, mine));

        // and it stays invisible to everyone else
        assertTrue(service.getSightingsFor(theirs).isEmpty());
        assertNull(service.getSightingFor(UID, theirs));
    }

    @Test
    void assigningSomethingNeverSeenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.assign("neverseen", TenantId.fromUUID(UUID.randomUUID())));
    }

    private InferrixControllerSighting awaitSighting(String uid) {
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.getSighting(uid) != null);
        InferrixControllerSighting sighting = service.getSighting(uid);
        assertNotNull(sighting);
        return sighting;
    }

    private String announce(String payload) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", service.getBoundPort()), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8) : "";
        }
    }

}
