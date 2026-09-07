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
import lombok.Getter;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One controller seen announcing itself on the discovery port, before anyone has adopted it.
 *
 * <p>Everything here is <b>unauthenticated device-supplied data</b>: the announce is a plain TCP
 * connect with no credential of any kind (that is the point — it has to work before a broker, a
 * password or a certificate exists). Treat a sighting as a hint that something is at an address,
 * never as proof of what it is. Identity is only established during adoption, when the platform
 * connects back over TLS and pins the certificate.
 */
@Getter
public class InferrixControllerSighting {

    private final String uid;
    private final String sourceIp;
    private final JsonNode identity;
    private final long firstSeenTs;
    private volatile long lastSeenTs;

    /**
     * Which tenant is allowed to see and adopt this controller, or null while it belongs to nobody.
     *
     * <p>A controller announcing on the network has no tenant of its own — nothing in the announce
     * says who owns the hardware, and nothing could, since it is unauthenticated. Deciding that is a
     * platform-operator judgement, so an unassigned sighting is visible only to a system
     * administrator. Without this, every tenant administrator on the installation could enumerate
     * every controller on the network: addresses, deployment names and locations included.
     */
    private volatile TenantId assignedTenantId;

    private final AtomicInteger announceCount = new AtomicInteger();

    public InferrixControllerSighting(String uid, String sourceIp, JsonNode identity, long ts) {
        this.uid = uid;
        this.sourceIp = sourceIp;
        this.identity = identity;
        this.firstSeenTs = ts;
        this.lastSeenTs = ts;
        this.announceCount.set(1);
    }

    /**
     * Folds a repeat announce into the existing sighting. The identity payload is deliberately kept
     * immutable at first-seen: a sighting is evidence of one claim, and letting later unauthenticated
     * packets rewrite it would let anything on the network mutate what the operator is shown just
     * before they click adopt.
     */
    public void recordRepeat(long ts) {
        this.lastSeenTs = ts;
        this.announceCount.incrementAndGet();
    }

    /**
     * The address the platform will connect back to. Taken from the TCP peer, never from the
     * announce body's {@code ip} field — the body is unauthenticated and a spoofed one would point
     * adoption at a host of the sender's choosing.
     */
    public TenantId getAssignedTenantId() {
        return assignedTenantId;
    }

    void assignTo(TenantId tenantId) {
        this.assignedTenantId = tenantId;
    }

    public boolean isVisibleTo(TenantId tenantId) {
        return assignedTenantId != null && assignedTenantId.equals(tenantId);
    }

    public int getAnnounceCount() {
        return announceCount.get();
    }

    public String getConnectBackIp() {
        return sourceIp;
    }

}
