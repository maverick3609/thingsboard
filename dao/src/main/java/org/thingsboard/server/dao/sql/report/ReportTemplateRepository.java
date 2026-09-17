// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.ExportableEntityRepository;
import org.thingsboard.server.dao.model.sql.ReportTemplateEntity;

import java.util.List;
import java.util.UUID;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplateEntity, UUID>, ExportableEntityRepository<ReportTemplateEntity> {

    Long countByTenantId(UUID tenantId);

    Page<ReportTemplateEntity> findByTenantId(UUID tenantId, Pageable pageable);

    List<ReportTemplateEntity> findByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

    @Query("SELECT externalId FROM ReportTemplateEntity WHERE id = :id")
    UUID getExternalIdById(@Param("id") UUID id);

}
