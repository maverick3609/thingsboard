// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.timewindow;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.kv.Aggregation;

@Data
@NoArgsConstructor
public class AggregationConfiguration {

    private Aggregation type;
    private int limit;
}
