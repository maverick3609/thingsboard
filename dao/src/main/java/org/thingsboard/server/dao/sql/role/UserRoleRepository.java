// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.UserRoleCompositeKey;
import org.thingsboard.server.dao.model.sql.UserRoleEntity;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleCompositeKey> {

    @Query("SELECT e.userId FROM UserRoleEntity e WHERE e.roleId = :roleId")
    List<UUID> findUserIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * Lock the roles about to be assigned, in a caller-fixed order. A role delete locks the role
     * row before it touches user_role, so the assignment has to take the same two in the same
     * order or the two transactions deadlock over one another's rows.
     */
    @Query(value = "SELECT id FROM role WHERE id IN (:roleIds) ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockRoles(@Param("roleIds") List<UUID> roleIds);

    /**
     * Lock the user row so two concurrent replace-sets for the same user cannot interleave:
     * without it the second DELETE runs against a snapshot that does not see the first
     * transaction's freshly inserted rows, and the two role sets merge instead of the last
     * one winning - handing the user the union of both grants.
     */
    @Query(value = "SELECT id FROM tb_user WHERE id = :userId FOR UPDATE", nativeQuery = true)
    UUID lockUser(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserRoleEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);

}
