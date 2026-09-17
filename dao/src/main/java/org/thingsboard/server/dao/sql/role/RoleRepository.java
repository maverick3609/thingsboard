// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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


    /**
     * Lock the role row so a concurrent role assignment cannot slip in while the role is being
     * deleted. Inserting a user_role row takes a FOR KEY SHARE lock on the role it references,
     * which conflicts with FOR UPDATE — so once this returns, the assignee list the caller reads
     * cannot go stale before the delete commits, and the blocked assignment then fails its
     * foreign key rather than leaving a user holding a deleted role's grants.
     */
    @Query(value = "SELECT id FROM role WHERE id = :roleId AND tenant_id = :tenantId FOR UPDATE", nativeQuery = true)
    UUID lockRole(@Param("tenantId") UUID tenantId, @Param("roleId") UUID roleId);

}
