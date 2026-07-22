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
