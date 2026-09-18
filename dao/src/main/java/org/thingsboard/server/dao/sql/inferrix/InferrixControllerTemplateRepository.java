// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.inferrix;

import org.springframework.data.jpa.repository.JpaRepository;
import org.thingsboard.server.dao.model.sql.InferrixControllerTemplateEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InferrixControllerTemplateRepository extends JpaRepository<InferrixControllerTemplateEntity, UUID> {

    /**
     * The picker's list.
     *
     * <p>A closed projection rather than the entities or a JPQL constructor expression: the payload
     * of a template taken from a controller with a thousand points is far too big to send once per
     * row, and a derived query with a projection is checked by the compiler-visible method name
     * instead of by a query string that would only fail when the application starts.
     */
    List<TemplateSummary> findByTenantIdOrderByCreatedTimeDesc(UUID tenantId);

    /** Always by tenant as well as id: a template id from another tenant must read as absent. */
    Optional<InferrixControllerTemplateEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<InferrixControllerTemplateEntity> findByTenantIdAndName(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);

    void deleteByTenantIdAndId(UUID tenantId, UUID id);

    /** Everything about a saved configuration except the configuration. */
    interface TemplateSummary {
        UUID getId();

        long getCreatedTime();

        String getName();

        String getSourceName();

        int getRecordCount();

        boolean isHasLogic();
    }

}
