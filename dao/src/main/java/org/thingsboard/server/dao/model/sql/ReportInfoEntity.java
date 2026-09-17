// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.common.data.report.ReportInfo;

import java.util.HashMap;
import java.util.Map;

import static org.thingsboard.server.dao.model.ModelConstants.REPORT_TABLE_NAME;

/**
 * List projection of {@code report}, enriched with the owning customer's title via a LEFT JOIN on
 * {@code customer} (see {@code ReportInfoRepository}). {@code customerTitle} has no backing column
 * on this table, hence {@code @Transient} — it is only ever populated by the join-based
 * constructor-projection queries, never by a plain load of this entity.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = REPORT_TABLE_NAME)
public final class ReportInfoEntity extends AbstractReportEntity<ReportInfo> {

    public static final Map<String, String> reportInfoColumnMap = new HashMap<>();
    static {
        reportInfoColumnMap.put("customerTitle", "c.title");
    }

    @Transient
    private String customerTitle;

    public ReportInfoEntity() {
        super();
    }

    public ReportInfoEntity(ReportInfo reportInfo) {
        super(reportInfo);
        this.customerTitle = reportInfo.getCustomerTitle();
    }

    public ReportInfoEntity(ReportEntity entity, String customerTitle) {
        super(entity);
        this.customerTitle = customerTitle;
    }

    public ReportInfo toData() {
        ReportInfo reportInfo = new ReportInfo(toReport());
        reportInfo.setCustomerTitle(customerTitle);
        return reportInfo;
    }

}
