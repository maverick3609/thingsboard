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
