// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;

/**
 * Renders an {@code ENTITY_TABLE} (PE {@code report.renderer.EntityTableRenderer}): one row per
 * permission-scoped entity, columns from the source's data keys. All rows come from the engine's {@code
 * collectEntityDatas} build — this renderer only lays them out via the {@link
 * TableWithLayoutComponentRenderer} base.
 */
@Component
public class EntityTableRenderer extends TableWithLayoutComponentRenderer<EntityTableComponent> {

    @Override
    protected String dataSourceName() {
        return "data source";
    }

    @Override
    protected String noDataMessage() {
        return "No entities found";
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ENTITY_TABLE;
    }
}
