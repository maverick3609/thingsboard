// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import org.thingsboard.server.common.data.report.configuration.TableSortOrder;
import org.thingsboard.server.common.data.report.configuration.style.Heading;

public interface TableReportComponent extends DataReportComponent {
    boolean isShowTableHeading();

    Heading getTableHeading();

    TableSortOrder getTableSortOrder();
}
