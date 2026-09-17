// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import lombok.Data;
import org.thingsboard.server.common.data.report.configuration.style.DataKeySettingsType;

@Data
public class DefaultDataKeySettings implements DataKeySettings {

    @Override
    public DataKeySettingsType getType() {
        return DataKeySettingsType.DEFAULT;
    }
}
