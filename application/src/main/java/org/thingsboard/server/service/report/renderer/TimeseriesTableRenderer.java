// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.TimeseriesTableComponent;

import java.util.LinkedList;
import java.util.List;

/**
 * Renders a {@code TIME_SERIES_TABLE} (PE {@code report.renderer.TimeseriesTableRenderer}): one row per
 * timestamp of a single entity's series (the engine resolves the entity and builds the rows via {@code
 * buildTsComponentData}). Prepends a synthetic {@code ts} timestamp column when {@code showTimestamp} is set,
 * using the component's timestamp label/column-settings, then the source's series columns from the base.
 */
@Component
public class TimeseriesTableRenderer extends TableWithLayoutComponentRenderer<TimeseriesTableComponent> {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.TIME_SERIES_TABLE;
    }

    @Override
    protected String noDataMessage() {
        return "No time series data found";
    }

    @Override
    protected List<DataKey> getColumns(TimeseriesTableComponent component, DataSource dataSource) {
        LinkedList<DataKey> columns = new LinkedList<>();
        if (component.isShowTimestamp()) {
            DataKey tsDataKey = DataKey.builder()
                    .name("ts")
                    .label(component.getTimestampLabel())
                    .settings(component.getTimestampColumnSettings())
                    .usePostProcessing(false)
                    .build();
            columns.add(tsDataKey);
        }
        columns.addAll(super.getColumns(component, dataSource));
        return columns;
    }
}
