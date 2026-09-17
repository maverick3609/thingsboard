// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.ReportInfoEntity;

import java.util.UUID;

/**
 * Backed by {@code ReportInfoEntity} (an independent {@code @Entity} mapped onto the same
 * {@code report} table as {@code ReportEntity}, mirroring the report_template Entity/InfoEntity
 * split). The customer-title join queries source FROM {@code ReportEntity} and project into
 * {@code ReportInfoEntity} via a constructor expression - same shape as
 * {@code ReportTemplateInfoRepository}.
 */
public interface ReportInfoRepository extends JpaRepository<ReportInfoEntity, UUID> {

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportInfoEntity(r, c.title) " +
            "FROM ReportEntity r LEFT JOIN CustomerEntity c ON c.id = r.customerId " +
            "WHERE r.id = :id")
    ReportInfoEntity findReportInfoById(@Param("id") UUID id);

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportInfoEntity(r, c.title) " +
            "FROM ReportEntity r LEFT JOIN CustomerEntity c ON c.id = r.customerId " +
            "WHERE r.tenantId = :tenantId AND (r.customerId IS NULL OR r.customerId = :nullCustomerId) " +
            "AND (:reportTemplateId IS NULL OR r.templateId = :reportTemplateId) " +
            "AND (:userId IS NULL OR r.userId = :userId) " +
            "AND (:searchText IS NULL OR ilike(r.name, CONCAT('%', :searchText, '%')) = true)")
    Page<ReportInfoEntity> findTenantReportInfos(@Param("tenantId") UUID tenantId,
                                                  @Param("nullCustomerId") UUID nullCustomerId,
                                                  @Param("reportTemplateId") UUID reportTemplateId,
                                                  @Param("userId") UUID userId,
                                                  @Param("searchText") String searchText,
                                                  Pageable pageable);

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportInfoEntity(r, c.title) " +
            "FROM ReportEntity r LEFT JOIN CustomerEntity c ON c.id = r.customerId " +
            "WHERE r.tenantId = :tenantId " +
            "AND (:reportTemplateId IS NULL OR r.templateId = :reportTemplateId) " +
            "AND (:userId IS NULL OR r.userId = :userId) " +
            "AND (:searchText IS NULL OR ilike(r.name, CONCAT('%', :searchText, '%')) = true " +
            "  OR ilike(c.title, CONCAT('%', :searchText, '%')) = true)")
    Page<ReportInfoEntity> findTenantReportInfosIncludingCustomers(@Param("tenantId") UUID tenantId,
                                                                    @Param("reportTemplateId") UUID reportTemplateId,
                                                                    @Param("userId") UUID userId,
                                                                    @Param("searchText") String searchText,
                                                                    Pageable pageable);

}
