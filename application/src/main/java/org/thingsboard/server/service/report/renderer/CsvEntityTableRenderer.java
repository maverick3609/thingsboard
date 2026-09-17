// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;

/**
 * Renders an {@code ENTITY_TABLE} to CSV (PE {@code report.renderer.CsvEntityTableRenderer}): one row per
 * permission-scoped entity, columns from the source's data keys — all layout is the {@link
 * AbstractCsvComponentRenderer} base. A plain {@code @Component} (like the PDF table renderers): stateless,
 * injects nothing, so it boots renderer-off and is only collected by the gated {@code CsvReportService}.
 */
@Component
public class CsvEntityTableRenderer extends AbstractCsvComponentRenderer<EntityTableComponent> {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ENTITY_TABLE;
    }

}
