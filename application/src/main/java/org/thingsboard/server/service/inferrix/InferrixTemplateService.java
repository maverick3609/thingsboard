// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.model.sql.InferrixControllerTemplateEntity;
import org.thingsboard.server.dao.sql.inferrix.InferrixControllerTemplateRepository;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.List;
import java.util.UUID;

/**
 * Saved controller configurations, kept so one can be rolled back or put on a second controller.
 *
 * <p>A template holds the config plane sections and/or a logic program and nothing else. Network
 * addressing, MQTT, identity and the controller's ownership password are deliberately outside it:
 * they are per-controller by nature, and a template that carried a password would hand every
 * controller the first one's credentials the moment it was applied.
 *
 * <p>Templates are tenant-private. Every read and write takes the tenant from the caller's session
 * and filters on it, so a guessed id from another tenant reads as absent rather than as forbidden.
 */
@Service
@TbCoreComponent
@RequiredArgsConstructor
public class InferrixTemplateService {

    /** A controller holds at most 1024 points; 4 MB of JSON is far more than a full one serialises to. */
    private static final int MAX_PAYLOAD_CHARS = 4 * 1024 * 1024;
    private static final int MAX_TEMPLATES_PER_TENANT = 200;
    private static final int MAX_NAME_LENGTH = 255;

    private final InferrixControllerTemplateRepository repository;

    public List<ControllerTemplateSummary> list(TenantId tenantId) {
        return repository.findByTenantIdOrderByCreatedTimeDesc(tenantId.getId()).stream()
                .map(row -> new ControllerTemplateSummary(row.getId().toString(), row.getCreatedTime(),
                        row.getName(), row.getSourceName(), row.getRecordCount(), row.isHasLogic()))
                .toList();
    }

    public ControllerTemplate get(TenantId tenantId, UUID id) {
        return repository.findByTenantIdAndId(tenantId.getId(), id)
                .map(InferrixTemplateService::toData)
                .orElse(null);
    }

    /**
     * Saves under a name, replacing whatever that name held.
     *
     * <p>Replace rather than refuse: the name is the operator's own label, and the page that sends
     * this already lists what exists, so a collision is a decision the operator has made rather than
     * an error to report. The id therefore stays stable across a re-save.
     */
    @Transactional
    public ControllerTemplate save(TenantId tenantId, SaveTemplateRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A template needs a name of 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (request.config() == null && request.logic() == null) {
            throw new IllegalArgumentException("A template needs a configuration, a logic program, or both");
        }
        checkSize(request.config());
        checkSize(request.logic());

        InferrixControllerTemplateEntity entity = repository
                .findByTenantIdAndName(tenantId.getId(), name)
                .orElseGet(InferrixControllerTemplateEntity::new);
        if (entity.getId() == null) {
            if (repository.countByTenantId(tenantId.getId()) >= MAX_TEMPLATES_PER_TENANT) {
                throw new IllegalArgumentException("This tenant already holds "
                        + MAX_TEMPLATES_PER_TENANT + " templates; delete one first");
            }
            entity.setId(UUID.randomUUID());
            entity.setCreatedTime(System.currentTimeMillis());
            entity.setTenantId(tenantId.getId());
        }
        entity.setName(name);
        entity.setSourceName(trimToNull(request.sourceName()));
        entity.setConfig(request.config());
        entity.setLogic(request.logic());
        // Counted here, never taken from the caller: it is what the picker shows about a payload it
        // does not download.
        entity.setRecordCount(countRecords(request.config()));
        entity.setHasLogic(request.logic() != null && !request.logic().isNull());
        return toData(repository.save(entity));
    }

    /** Silent when the id is another tenant's or already gone: the caller asked for it to not exist. */
    @Transactional
    public void delete(TenantId tenantId, UUID id) {
        repository.deleteByTenantIdAndId(tenantId.getId(), id);
    }

    private static void checkSize(JsonNode payload) {
        if (payload != null && payload.toString().length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("Template payload is too large");
        }
    }

    /** Sum of the section arrays. A section the controller does not use is absent, not empty. */
    private static int countRecords(JsonNode config) {
        if (config == null || !config.isObject()) {
            return 0;
        }
        int total = 0;
        for (JsonNode section : config) {
            if (section != null && section.isArray()) {
                total += section.size();
            }
        }
        return total;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null
                : trimmed.substring(0, Math.min(trimmed.length(), MAX_NAME_LENGTH));
    }

    private static ControllerTemplate toData(InferrixControllerTemplateEntity entity) {
        return new ControllerTemplate(entity.getId().toString(), entity.getCreatedTime(), entity.getName(),
                entity.getSourceName(), entity.getRecordCount(), entity.isHasLogic(),
                entity.getConfig(), entity.getLogic());
    }

    public record SaveTemplateRequest(String name, String sourceName, JsonNode config, JsonNode logic) {
    }

    /** One row of the picker: everything about a saved configuration except the configuration. */
    public record ControllerTemplateSummary(String id, long createdTime, String name, String sourceName,
                                            int recordCount, boolean hasLogic) {
    }

    public record ControllerTemplate(String id, long createdTime, String name, String sourceName,
                                     int recordCount, boolean hasLogic, JsonNode config, JsonNode logic) {
    }

}
