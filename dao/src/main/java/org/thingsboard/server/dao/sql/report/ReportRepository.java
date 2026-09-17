// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.dao.model.sql.ReportEntity;

import java.util.UUID;

public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {

    // INLINE bytea storage (not a Postgres large object / oid) - the 'data' column is a plain
    // bytea on the 'report' table, so these are verbatim native queries, not the
    // OtaPackageRepository lo_creat/lo_unlink large-object pattern.
    @Modifying
    @Transactional
    @Query(value = "UPDATE report SET data = :data WHERE id = :id", nativeQuery = true)
    void saveData(@Param("id") UUID id, @Param("data") byte[] data);

    @Query(value = "SELECT data FROM report WHERE id = :id", nativeQuery = true)
    byte[] getDataById(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReportEntity r WHERE r.tenantId = :tenantId")
    void deleteByTenantId(@Param("tenantId") UUID tenantId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReportEntity r WHERE r.tenantId = :tenantId AND r.customerId = :customerId")
    void deleteByTenantIdAndCustomerId(@Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);

}
