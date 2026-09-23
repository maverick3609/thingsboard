// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.inferrix.gateway.InferrixGatewayClient.GatewayResponse;

import java.io.IOException;
import java.time.Duration;

/**
 * The gateway's own description of every model type it supports, fetched once and reused.
 *
 * <p>This is what makes schema-driven forms possible: the gateway's web app hand-codes a component
 * per data source type, and copying roughly seventy of those into Cortex would *be* the project.
 * Instead the gateway serves JSON Schema and Cortex renders it.
 *
 * <p><b>The whole document is cached, not one entry per type.</b> Nested types are {@code $ref}s
 * into the document's own {@code components.schemas}, so a per-type cache would either duplicate
 * that block in every entry or hold entries whose refs point at something the cache no longer has.
 *
 * <p><b>Keyed by device.</b> Two gateways can legitimately run different stack versions with
 * different fields, and a shared entry would render a form for inputs the hardware does not have.
 *
 * <p>In-memory and per-node, like {@code InferrixUploadService}'s job cache. A node that restarts
 * re-fetches; the document only changes when the gateway's build does.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class InferrixGatewaySchemaService {

    /** Allowlisted, and readable by a non-administrator — the forms are useless otherwise. */
    public static final String SCHEMAS_PATH = "/v2/model-schemas";

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int MAX_GATEWAYS = 512;

    private final Cache<DeviceId, JsonNode> schemas = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(MAX_GATEWAYS)
            .build();

    private final InferrixGatewayAccess access;

    /**
     * @return the gateway's whole schema document, from cache when it is there
     * @throws IOException if the gateway could not be reached, refused, or answered something that
     *                     is not a document — in every case <b>nothing is cached</b>, so a gateway
     *                     that was down when someone first opened a form does not stay unusable
     *                     for the rest of the TTL
     */
    public JsonNode schemas(TenantId tenantId, DeviceId deviceId) throws Exception {
        JsonNode cached = schemas.getIfPresent(deviceId);
        if (cached != null) {
            return cached;
        }
        // Deliberately not Cache.get(key, mappingFunction): that would need the checked exceptions
        // wrapped, and a mapping function that throws is exactly the case where the behaviour has
        // to be "do not remember this".
        GatewayResponse response = access.call(tenantId, deviceId, "GET", SCHEMAS_PATH, null, null);
        if (response.statusCode() != 200) {
            throw new IOException("The gateway did not serve its model schemas: HTTP "
                    + response.statusCode());
        }
        JsonNode document = parse(response.body());
        schemas.put(deviceId, document);
        return document;
    }

    /**
     * Drops one gateway's document.
     *
     * <p>Called on re-adoption: that gateway may be replacement hardware on a different stack
     * version, and serving the old document would describe fields the new box does not have.
     */
    public void forget(DeviceId deviceId) {
        schemas.invalidate(deviceId);
    }

    private static JsonNode parse(String body) throws IOException {
        JsonNode document;
        try {
            document = JacksonUtil.toJsonNode(body);
        } catch (RuntimeException e) {
            throw new IOException("The gateway's model schemas were not readable as JSON", e);
        }
        // A null or non-object answer must not be cached as though it were a document: every later
        // caller would then get an empty form with no indication that anything went wrong.
        if (document == null || !document.has("families")) {
            throw new IOException("The gateway's model schemas were not readable as JSON");
        }
        return document;
    }
}
