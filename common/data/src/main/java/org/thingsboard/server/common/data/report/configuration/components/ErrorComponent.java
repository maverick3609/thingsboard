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
package org.thingsboard.server.common.data.report.configuration.components;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Schema
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorComponent implements ReportComponent {

    private String errorMessage;
    // The carried exception is transient render state (never serialized, no value semantics) — exclude it
    // from equals/hashCode/toString so two structurally-identical configs compare equal.
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Exception exception;

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ERROR;
    }
}
