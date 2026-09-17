// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class EntityTableComponent extends AbstractTableWithLayoutReportComponent {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ENTITY_TABLE;
    }
}
