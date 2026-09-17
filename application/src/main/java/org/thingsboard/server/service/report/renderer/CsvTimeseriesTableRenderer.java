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
import java.util.Optional;

/**
 * Renders a {@code TIME_SERIES_TABLE} to CSV (PE {@code report.renderer.CsvTimeseriesTableRenderer}): one row
 * per timestamp of a single entity's series (the engine resolves the entity and builds the rows via {@code
 * buildTsComponentData}). Prepends a synthetic {@code ts} timestamp column when {@code showTimestamp} is set
 * — using the component's timestamp label / column-settings — then the source's series and latest columns.
 * Mirrors the PDF {@link TimeseriesTableRenderer#getColumns}. Plain {@code @Component}: stateless, injects
 * nothing, boots renderer-off, collected only by the gated {@code CsvReportService}.
 */
@Component
public class CsvTimeseriesTableRenderer extends AbstractCsvComponentRenderer<TimeseriesTableComponent> {

    @Override
    protected List<DataKey> getColumns(TimeseriesTableComponent component, DataSource dataSource) {
        LinkedList<DataKey> columns = new LinkedList<>();
        if (component.isShowTimestamp()) {
            // ColumnSettings widens to DataKeySettings (DataKey#settings) in CE — no cast, unlike PE.
            columns.add(DataKey.builder()
                    .name("ts")
                    .label(component.getTimestampLabel())
                    .settings(component.getTimestampColumnSettings())
                    .usePostProcessing(false)
                    .build());
        }
        Optional.ofNullable(dataSource.getDataKeys()).ifPresent(columns::addAll);
        Optional.ofNullable(dataSource.getLatestDataKeys()).ifPresent(columns::addAll);
        return columns;
    }

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.TIME_SERIES_TABLE;
    }

}
