// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;

import java.util.List;

@Schema
@Data
@NoArgsConstructor
public class HeaderFooter {

    private boolean enabled;
    @NotNull
    private List<ReportComponent> components;
    private HeaderFooter firstPage;
}
