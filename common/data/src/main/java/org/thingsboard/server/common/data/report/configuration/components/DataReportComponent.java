// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import org.thingsboard.server.common.data.report.configuration.DataSource;

import java.util.List;

public interface DataReportComponent extends ReportComponent {
    List<DataSource> getDataSources();
}
