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
package org.thingsboard.server.dao.sql.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity;

import java.util.List;
import java.util.UUID;

/**
 * Backed by {@code ReportTemplateInfoEntity} (an independent {@code @Entity} mapped onto the same
 * {@code report_template} table as {@code ReportTemplateEntity}, mirroring the scheduler_event
 * Entity/InfoEntity split). The customer-title join queries source FROM {@code ReportTemplateEntity}
 * and project into {@code ReportTemplateInfoEntity} via a constructor expression — the same shape as
 * {@code SchedulerEventInfoRepository.findSchedulerEventWithCustomerInfoById}.
 */
public interface ReportTemplateInfoRepository extends JpaRepository<ReportTemplateInfoEntity, UUID> {

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity(rt, c.title) " +
            "FROM ReportTemplateEntity rt LEFT JOIN CustomerEntity c ON c.id = rt.customerId " +
            "WHERE rt.id = :id")
    ReportTemplateInfoEntity findReportTemplateInfoById(@Param("id") UUID id);

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity(rt, c.title) " +
            "FROM ReportTemplateEntity rt LEFT JOIN CustomerEntity c ON c.id = rt.customerId " +
            "WHERE rt.tenantId = :tenantId AND (rt.customerId IS NULL OR rt.customerId = :nullCustomerId) " +
            "AND (:searchText IS NULL OR ilike(rt.name, CONCAT('%', :searchText, '%')) = true) " +
            "AND ((:#{#typeList == null} = true) OR rt.type IN (:typeList)) " + //HHH-15968
            "AND ((:#{#formatList == null} = true) OR rt.format IN (:formatList))") //HHH-15968
    Page<ReportTemplateInfoEntity> findTenantReportTemplates(@Param("tenantId") UUID tenantId,
                                                              @Param("nullCustomerId") UUID nullCustomerId,
                                                              @Param("searchText") String searchText,
                                                              @Param("typeList") List<ReportTemplateType> typeList,
                                                              @Param("formatList") List<TbReportFormat> formatList,
                                                              Pageable pageable);

    @Query("SELECT NEW org.thingsboard.server.dao.model.sql.ReportTemplateInfoEntity(rt, c.title) " +
            "FROM ReportTemplateEntity rt LEFT JOIN CustomerEntity c ON c.id = rt.customerId " +
            "WHERE rt.tenantId = :tenantId " +
            "AND (:searchText IS NULL OR ilike(rt.name, CONCAT('%', :searchText, '%')) = true " +
            "  OR ilike(c.title, CONCAT('%', :searchText, '%')) = true) " +
            "AND ((:#{#typeList == null} = true) OR rt.type IN (:typeList)) " + //HHH-15968
            "AND ((:#{#formatList == null} = true) OR rt.format IN (:formatList))") //HHH-15968
    Page<ReportTemplateInfoEntity> findTenantReportTemplatesIncludingCustomers(@Param("tenantId") UUID tenantId,
                                                                                @Param("searchText") String searchText,
                                                                                @Param("typeList") List<ReportTemplateType> typeList,
                                                                                @Param("formatList") List<TbReportFormat> formatList,
                                                                                Pageable pageable);

    @Query("SELECT ri.id FROM ReportTemplateInfoEntity ri WHERE ri.tenantId = :tenantId")
    List<UUID> findIdsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT ri.id FROM ReportTemplateInfoEntity ri WHERE ri.tenantId = :tenantId AND ri.customerId = :customerId")
    List<UUID> findIdsByTenantIdAndCustomerId(@Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);

}
