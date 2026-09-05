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
package org.thingsboard.server.dao.sql.role;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.RoleEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Long countByTenantId(UUID tenantId);

    @Query("SELECT r FROM RoleEntity r WHERE r.tenantId = :tenantId " +
            "AND (:textSearch IS NULL OR ilike(r.name, CONCAT('%', :textSearch, '%')) = true)")
    Page<RoleEntity> findByTenantId(@Param("tenantId") UUID tenantId,
                                    @Param("textSearch") String textSearch,
                                    Pageable pageable);

    Optional<RoleEntity> findByTenantIdAndName(UUID tenantId, String name);

    List<RoleEntity> findByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

    @Query("SELECT r FROM RoleEntity r, UserRoleEntity ur WHERE ur.roleId = r.id " +
            "AND ur.userId = :userId AND r.tenantId = :tenantId")
    List<RoleEntity> findByUserId(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

}
