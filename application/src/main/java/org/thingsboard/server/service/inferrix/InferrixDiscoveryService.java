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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Listens for the Inferrix controller's plain-TCP discovery announce and keeps what it hears in
 * memory so an operator can adopt a controller nobody has configured yet.
 *
 * <p><b>Why this exists at all.</b> A controller can only appear over MQTT once it already holds
 * platform credentials, so MQTT can never surface a controller that has not been adopted. The
 * firmware's answer is an unauthenticated announce to {@code settings_disc} host:port (default
 * {@code gateway:9700}) that works before any broker, password or certificate exists
 * ({@code src/comms/discovery.c} in the controller repo). The device connects, writes its identity
 * JSON, and reads a reply; any reply containing {@code "ack"} and {@code true} stops it announcing.
 *
 * <p><b>Security posture.</b> This is an unauthenticated listener on a network port, so:
 * <ul>
 *   <li>it is <b>disabled by default</b> and must be switched on deliberately;</li>
 *   <li>it never writes to the database — a sighting is an in-memory hint, and creating anything is
 *       an authenticated operator action in {@link InferrixAdoptionService};</li>
 *   <li>every read is bounded (socket timeout, byte cap, connection cap, entry cap) so a hostile or
 *       broken peer cannot hold a thread or grow the heap;</li>
 *   <li>the connect-back address is taken from the TCP peer, not the announce body.</li>
 * </ul>
 *
 * <p>ponytail: sightings live in a per-node cache, not a table. They are ephemeral by nature — an
 * unacked controller re-announces every {@code period_s} (default 300 s), so a restart repopulates
 * within five minutes and no durable state is worth the DDL. The ceiling is a multi-node install
 * behind a load balancer, where sightings scatter across nodes and the operator sees whichever node
 * served their request. Upgrade path when that matters: move this cache behind the existing
 * distributed cache abstraction rather than adding an entity.
 */
@Service
@TbCoreComponent
@ConditionalOnProperty(prefix = "inferrix.controller.discovery", value = "enabled", havingValue = "true")
@Slf4j
public class InferrixDiscoveryService {

    /** Matches the device's own per-attempt budget (DISC_BUDGET_MS): it will not wait longer. */
    private static final int SOCKET_TIMEOUT_MS = 2000;

    /** The identity payload is a flat object of short strings; anything larger is not one. */
    private static final int MAX_ANNOUNCE_BYTES = 4096;

    /**
     * Grace period for the rest of an announce after the first bytes land. Only reached when what
     * has arrived does not parse yet, so a normal single-segment announce never pays it and a peer
     * sending junk cannot hold a worker for the full socket timeout.
     */
    private static final int CONTINUATION_TIMEOUT_MS = 250;

    private static final int MAX_SIGHTINGS = 512;
    private static final Duration SIGHTING_TTL = Duration.ofMinutes(30);

    /** Announces are seconds apart per device and trivially short; a small pool cannot be starved. */
    private static final int WORKER_THREADS = 4;

    /**
     * Bounded on purpose. A fixed thread pool queues without limit, which would let anything that
     * can reach the port pile up sockets — and their file descriptors — until the node runs out.
     * Past this depth an announce is refused and the device simply retries in period_s.
     */
    private static final int WORKER_QUEUE_DEPTH = 64;

    private static final String ACK_REPLY = "{\"ack\":true}";

    @Value("${inferrix.controller.discovery.bind_address:0.0.0.0}")
    private String bindAddress;

    @Value("${inferrix.controller.discovery.port:9700}")
    private int port;

    private final Cache<String, InferrixControllerSighting> sightings = Caffeine.newBuilder()
            .expireAfterWrite(SIGHTING_TTL)
            .maximumSize(MAX_SIGHTINGS)
            .build();

    private volatile ServerSocket serverSocket;
    private volatile boolean stopping;
    private ExecutorService acceptExecutor;
    private ExecutorService workerExecutor;

    @PostConstruct
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        acceptExecutor = Executors.newSingleThreadExecutor(
                ThingsBoardThreadFactory.forName("inferrix-discovery-accept"));
        workerExecutor = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKER_QUEUE_DEPTH),
                ThingsBoardThreadFactory.forName("inferrix-discovery"),
                new ThreadPoolExecutor.AbortPolicy());
        acceptExecutor.submit(this::acceptLoop);
        log.info("Inferrix controller discovery listening on {}:{}", bindAddress, port);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        closeQuietly(serverSocket);
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    /** The port actually bound, which differs from the configured one only when it is 0 (tests). */
    int getBoundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    /**
     * Every sighting, most recently seen first. System-administrator view only — an unadopted
     * controller belongs to nobody, and this list is the whole picture of what is on the network.
     */
    public List<InferrixControllerSighting> getAllSightings() {
        List<InferrixControllerSighting> all = new ArrayList<>(sightings.asMap().values());
        all.sort(Comparator.comparingLong(InferrixControllerSighting::getLastSeenTs).reversed());
        return all;
    }

    /** Only what a system administrator has handed to this tenant. */
    public List<InferrixControllerSighting> getSightingsFor(TenantId tenantId) {
        return getAllSightings().stream().filter(s -> s.isVisibleTo(tenantId)).toList();
    }

    /** Unscoped lookup. Callers acting for a tenant must use {@link #getSightingFor}. */
    public InferrixControllerSighting getSighting(String uid) {
        return sightings.getIfPresent(uid);
    }

    /** The sighting only if this tenant may act on it, otherwise null. */
    public InferrixControllerSighting getSightingFor(String uid, TenantId tenantId) {
        InferrixControllerSighting sighting = sightings.getIfPresent(uid);
        return sighting != null && sighting.isVisibleTo(tenantId) ? sighting : null;
    }

    /**
     * Hands a controller to a tenant. Deliberately a separate, system-administrator-only step rather
     * than something a tenant administrator can do for itself: allocating physical hardware to a
     * customer is not self-service.
     */
    public InferrixControllerSighting assign(String uid, TenantId tenantId) {
        InferrixControllerSighting sighting = sightings.getIfPresent(uid);
        if (sighting == null) {
            throw new IllegalArgumentException("No announce has been seen from " + uid);
        }
        sighting.assignTo(tenantId);
        log.info("Inferrix controller {} assigned to tenant {}", uid, tenantId);
        return sighting;
    }

    /** Drops a sighting once it has been adopted, so the operator's list does not show stale work. */
    public void forget(String uid) {
        sightings.invalidate(uid);
    }

    private void acceptLoop() {
        while (!stopping) {
            Socket pending = null;
            try {
                pending = serverSocket.accept();
                Socket socket = pending;
                workerExecutor.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!stopping) {
                    log.warn("Inferrix discovery accept failed", e);
                }
            } catch (RejectedExecutionException e) {
                // Saturated. Close the socket here or the descriptor leaks for as long as the peer
                // keeps it open; the device retries in period_s.
                log.debug("Inferrix discovery is saturated, dropping a connection");
                closeQuietly(pending);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            String sourceIp = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : null;
            byte[] body = readBounded(s);
            // Reply first and unconditionally. The ack is what stops the device's 5-minute announce
            // loop, and withholding it over a payload we did not like just makes it announce forever.
            OutputStream out = s.getOutputStream();
            out.write(ACK_REPLY.getBytes(StandardCharsets.UTF_8));
            out.flush();
            record(sourceIp, body);
        } catch (IOException e) {
            log.debug("Inferrix discovery announce failed", e);
        } catch (RuntimeException e) {
            log.debug("Inferrix discovery announce was not usable", e);
        }
    }

    private void record(String sourceIp, byte[] body) {
        if (sourceIp == null || body.length == 0) {
            return;
        }
        JsonNode identity = tryParse(new String(body, StandardCharsets.UTF_8));
        if (identity == null || !identity.isObject()) {
            return;
        }
        String uid = identity.path("uid").asText(null);
        if (!isPlausibleUid(uid)) {
            log.debug("Ignoring an Inferrix announce from {} with no usable uid", sourceIp);
            return;
        }
        long now = System.currentTimeMillis();
        InferrixControllerSighting existing = sightings.getIfPresent(uid);
        if (existing != null && sourceIp.equals(existing.getSourceIp())) {
            existing.recordRepeat(now);
        } else {
            // A new uid, or the same uid now announcing from a different address (DHCP move, or
            // someone impersonating it). Either way the operator should see the current address,
            // and adoption re-verifies over TLS before trusting anything.
            sightings.put(uid, new InferrixControllerSighting(uid, sourceIp, identity, now));
            log.info("Inferrix controller {} announced from {}", uid, sourceIp);
        }
    }

    /**
     * The firmware emits a 24-hex silicon uid. Bound it here because the uid becomes a cache key, a
     * device name and part of an MQTT topic downstream.
     */
    static boolean isPlausibleUid(String uid) {
        if (uid == null || uid.isEmpty() || uid.length() > 64) {
            return false;
        }
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the announce without waiting for a close the device will never send — it writes its
     * identity and then blocks on the reply. Stops as soon as what has arrived parses as JSON, so
     * the usual single-segment announce costs no delay, and keeps reading when it does not, so an
     * announce split across TCP segments is not silently truncated into a parse failure. A peer that
     * sends nothing usable is bounded by the socket timeout and the byte cap.
     */
    private static byte[] readBounded(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[MAX_ANNOUNCE_BYTES];
        int total = 0;
        while (total < buffer.length) {
            int read;
            try {
                read = in.read(buffer, total, buffer.length - total);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (read < 0) {
                break;
            }
            total += read;
            if (tryParse(new String(buffer, 0, total, StandardCharsets.UTF_8)) != null) {
                break;
            }
            socket.setSoTimeout(CONTINUATION_TIMEOUT_MS);
        }
        byte[] body = new byte[total];
        System.arraycopy(buffer, 0, body, 0, total);
        return body;
    }

    /** Parses, or returns null. Anything can connect to this port, so junk is expected input. */
    private static JsonNode tryParse(String value) {
        try {
            return JacksonUtil.toJsonNode(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("Failed to close the Inferrix discovery socket", e);
            }
        }
    }

}
