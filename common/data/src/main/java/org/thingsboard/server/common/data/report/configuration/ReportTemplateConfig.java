// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "format")
@JsonSubTypes(value = {@JsonSubTypes.Type(value = PdfReportTemplateConfig.class, name = "PDF"), @JsonSubTypes.Type(value = CsvReportTemplateConfig.class, name = "CSV")})
public interface ReportTemplateConfig {
    String getNamePattern();

    String getTimeDataPattern();

    TbReportFormat getFormat();

    List<EntityAlias> getEntityAliases();

    List<Filter> getFilters();

    List<ReportComponent> getComponents();
}
