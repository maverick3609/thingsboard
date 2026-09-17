// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * {@link BaseReportTemplate} enriched with the owning customer's title (LEFT JOIN on customer)
 * for the report template list.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ReportTemplateInfo extends BaseReportTemplate {

    private String customerTitle;

    public ReportTemplateInfo() {
        super();
    }

    public ReportTemplateInfo(BaseReportTemplate reportTemplate) {
        super(reportTemplate);
    }

}
