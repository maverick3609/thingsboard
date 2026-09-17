// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(value = {
        @JsonSubTypes.Type(value = HeadingComponent.class, name = "HEADING"),
        @JsonSubTypes.Type(value = RichTextComponent.class, name = "RICH_TEXT"),
        @JsonSubTypes.Type(value = EntityTableComponent.class, name = "ENTITY_TABLE"),
        @JsonSubTypes.Type(value = PageBreakComponent.class, name = "PAGE_BREAK"),
        @JsonSubTypes.Type(value = TimeseriesTableComponent.class, name = "TIME_SERIES_TABLE"),
        @JsonSubTypes.Type(value = AlarmTableComponent.class, name = "ALARM_TABLE"),
        @JsonSubTypes.Type(value = DashboardComponent.class, name = "DASHBOARD"),
        @JsonSubTypes.Type(value = ImageComponent.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = SubReportComponent.class, name = "SUB_REPORT"),
        @JsonSubTypes.Type(value = ErrorComponent.class, name = "ERROR"),
        @JsonSubTypes.Type(value = DividerComponent.class, name = "DIVIDER")})
public interface ReportComponent extends Serializable {
    ReportComponentType getType();
}
