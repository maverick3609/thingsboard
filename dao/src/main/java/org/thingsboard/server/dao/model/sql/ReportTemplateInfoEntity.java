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
import org.thingsboard.server.common.data.report.ReportTemplateInfo;

import java.util.HashMap;
import java.util.Map;

import static org.thingsboard.server.dao.model.ModelConstants.REPORT_TEMPLATE_TABLE_NAME;

/**
 * List projection of {@code report_template}, enriched with the owning customer's title via a
 * LEFT JOIN on {@code customer} (see {@code ReportTemplateInfoRepository}). {@code customerTitle}
 * has no backing column on this table, hence {@code @Transient} — it is only ever populated by the
 * join-based constructor-projection queries, never by a plain load of this entity.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = REPORT_TEMPLATE_TABLE_NAME)
public final class ReportTemplateInfoEntity extends AbstractReportTemplateEntity<ReportTemplateInfo> {

    public static final Map<String, String> reportTemplateInfoColumnMap = new HashMap<>();
    static {
        reportTemplateInfoColumnMap.put("customerTitle", "c.title");
    }

    @Transient
    private String customerTitle;

    public ReportTemplateInfoEntity() {
        super();
    }

    public ReportTemplateInfoEntity(ReportTemplateInfo reportTemplateInfo) {
        super(reportTemplateInfo);
        this.customerTitle = reportTemplateInfo.getCustomerTitle();
    }

    public ReportTemplateInfoEntity(ReportTemplateEntity entity, String customerTitle) {
        super(entity);
        this.customerTitle = customerTitle;
    }

    public ReportTemplateInfo toData() {
        ReportTemplateInfo reportTemplateInfo = new ReportTemplateInfo(toBaseReportTemplate());
        reportTemplateInfo.setCustomerTitle(customerTitle);
        return reportTemplateInfo;
    }

}
