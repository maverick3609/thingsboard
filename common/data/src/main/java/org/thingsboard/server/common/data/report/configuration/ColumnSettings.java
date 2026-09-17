// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.report.configuration.style.DataKeySettingsType;

@Data
@NoArgsConstructor
public class ColumnSettings implements DataKeySettings {

    private String columnWidth;
    private CellSettings header;
    private CellSettings cell;

    @Override
    public DataKeySettingsType getType() {
        return DataKeySettingsType.COLUMN;
    }
}
