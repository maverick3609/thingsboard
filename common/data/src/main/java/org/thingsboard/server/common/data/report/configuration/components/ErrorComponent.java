// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
