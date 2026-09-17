// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.thingsboard.server.common.data.report.configuration.style.DataKeySettingsType;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", defaultImpl = DefaultDataKeySettings.class)
@JsonSubTypes(value = {@JsonSubTypes.Type(value = ColumnSettings.class, name = "COLUMN"), @JsonSubTypes.Type(value = DefaultDataKeySettings.class, name = "DEFAULT")})
public interface DataKeySettings {
    DataKeySettingsType getType();
}
