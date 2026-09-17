// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.thingsboard.server.common.data.page.PageLink;

import java.util.List;

/**
 * Paged report template query.
 */
@Data
@AllArgsConstructor
@Builder
public class ReportTemplateQuery {

    private PageLink pageLink;
    private List<ReportTemplateType> typeList;
    private List<TbReportFormat> formatList;
    private boolean includeCustomers;

}
