// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.report.configuration.DataSource;

import java.util.List;

@Schema
@Data
@NoArgsConstructor
public abstract class AbstractDataReportComponent implements DataReportComponent {

    private List<DataSource> dataSources;
}
