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
package org.thingsboard.server.dao.sql.scheduler;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.SchedulerEventInfoEntity;
import org.thingsboard.server.dao.model.sql.SchedulerEventWithCustomerInfoEntity;

import java.util.List;
import java.util.UUID;

public interface SchedulerEventInfoRepository extends JpaRepository<SchedulerEventInfoEntity, UUID> {

    @Query("SELECT new org.thingsboard.server.dao.model.sql.SchedulerEventWithCustomerInfoEntity(s, c.title, c.additionalInfo) " +
            "FROM SchedulerEventInfoEntity s LEFT JOIN CustomerEntity c on c.id = s.customerId WHERE s.id = :schedulerEventId")
    SchedulerEventWithCustomerInfoEntity findSchedulerEventWithCustomerInfoById(@Param("schedulerEventId") UUID schedulerEventId);

    List<SchedulerEventInfoEntity> findSchedulerEventInfoEntitiesByTenantId(UUID tenantId);

    List<SchedulerEventInfoEntity> findSchedulerEventInfoEntitiesByTenantIdAndEnabled(UUID tenantId, boolean enabled);

    @Query("SELECT s.id FROM SchedulerEventInfoEntity s WHERE s.tenantId = :tenantId AND s.customerId = :customerId")
    List<UUID> findIdsByTenantIdAndCustomerId(@Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);

    @Query("SELECT s.id FROM SchedulerEventInfoEntity s WHERE s.tenantId = :tenantId")
    List<UUID> findIdsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT new org.thingsboard.server.dao.model.sql.SchedulerEventWithCustomerInfoEntity(s, c.title, c.additionalInfo) " +
            "FROM SchedulerEventInfoEntity s LEFT JOIN CustomerEntity c on c.id = s.customerId " +
            "WHERE s.tenantId = :tenantId AND (:customerId IS NULL OR s.customerId = :customerId) AND (:type IS NULL OR s.type = :type) " +
            "AND (:searchText IS NULL OR ilike(s.name, CONCAT('%', :searchText, '%')) = true " +
            "  OR ilike(s.type, CONCAT('%', :searchText, '%')) = true " +
            "  OR ilike(c.title, CONCAT('%', :searchText, '%')) = true)")
    Page<SchedulerEventWithCustomerInfoEntity> findByTenantIdAndCustomerIdAndTypeAndSearchText(
            @Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId, @Param("type") String type,
            @Param("searchText") String searchText, Pageable pageable);

    List<SchedulerEventInfoEntity> findSchedulerEventsByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

}
