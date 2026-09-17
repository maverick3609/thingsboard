// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * {@link Report} enriched with the owning customer's title (LEFT JOIN on customer) for the
 * report history list.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ReportInfo extends Report {

    private String customerTitle;

    public ReportInfo() {
        super();
    }

    public ReportInfo(Report report) {
        super(report);
    }

}
