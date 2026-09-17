// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.report.configuration.timewindow.TimeWindowConfiguration;

@Schema
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataKey {

    private String name;
    private String type;
    private String label;
    private Integer decimals;
    private String units;
    private Aggregation aggregationType;
    private TimeWindowConfiguration timewindow;
    private boolean usePostProcessing;
    private String postFuncBody;
    private DataKeySettings settings;

    public DataKey(String name, String type, String label) {
        this.name = name;
        this.type = type;
        this.label = label;
    }
}
