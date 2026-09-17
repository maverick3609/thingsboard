// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.scheduler;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.ExportableEntityRepository;
import org.thingsboard.server.dao.model.sql.SchedulerEventEntity;

import java.util.UUID;

public interface SchedulerEventRepository extends JpaRepository<SchedulerEventEntity, UUID>, ExportableEntityRepository<SchedulerEventEntity> {

    Long countByTenantId(UUID tenantId);

    Page<SchedulerEventEntity> findByTenantId(UUID tenantId, Pageable pageable);

    @Query("SELECT externalId FROM SchedulerEventEntity WHERE id = :id")
    UUID getExternalIdById(@Param("id") UUID id);

}
