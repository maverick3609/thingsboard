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
