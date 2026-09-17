// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.page.PageLink;

/**
 * Paged report history query.
 */
@Data
@AllArgsConstructor
@Builder
public class ReportInfoQuery {

    private PageLink pageLink;
    private ReportTemplateId reportTemplateId;
    private UserId userId;
    private boolean includeCustomers;

}
