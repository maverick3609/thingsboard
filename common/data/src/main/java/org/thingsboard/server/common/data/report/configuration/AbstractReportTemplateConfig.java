// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;

import java.util.List;

@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@SuperBuilder
public abstract class AbstractReportTemplateConfig implements ReportTemplateConfig {

    protected String namePattern;
    protected String timeDataPattern;
    protected List<EntityAlias> entityAliases;
    protected List<Filter> filters;
    @NotNull
    protected List<ReportComponent> components;
}
