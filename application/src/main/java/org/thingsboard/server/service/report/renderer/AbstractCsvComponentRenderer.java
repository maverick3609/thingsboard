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
package org.thingsboard.server.service.report.renderer;

import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.TableSortOrder;
import org.thingsboard.server.common.data.report.configuration.components.TableReportComponent;
import org.thingsboard.server.common.data.report.configuration.style.Heading;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared CSV table renderer (PE {@code report.renderer.AbstractCsvComponentRenderer}): lays a table
 * component's already-resolved {@link ComponentData} out as rows — an optional heading row, a column-header
 * row, then one row per data row. Columns are the source's {@link DataKey}s ({@link #getColumns}, overridden
 * by {@link CsvTimeseriesTableRenderer} to prepend the synthetic timestamp column); each cell reuses the Task
 * G presentation helpers {@link ReportUtils#formatValueWithPrecisionAndUnits} (precision + units) and {@link
 * ReportUtils#sortRowsByTableSortOrder}, so PDF and CSV format identical values.
 * <p>
 * Values are emitted plain here. CSV escaping (structure AND the formula-injection guard) is concentrated in
 * {@code CsvUtils.generateCsv}, applied uniformly to every cell — the same separation the PDF path uses
 * ({@code table-template} HTML-escapes there, not in the data layer).
 */
public abstract class AbstractCsvComponentRenderer<C extends TableReportComponent>
        implements CsvReportComponentRenderer<C> {

    @Override
    public List<List<String>> render(C component, ComponentData componentData) {
        Optional<DataSource> dataSourceOpt = ReportUtils.getSingleDataSource(component);
        if (dataSourceOpt.isEmpty()) {
            return List.of(List.of("Data source is not configured for " + component.getType().name() + " component"));
        }
        DataSource dataSource = dataSourceOpt.get();
        List<DataKey> columns = getColumns(component, dataSource);
        List<List<String>> csvContent = new ArrayList<>();
        addHeading(component, componentData, csvContent);
        addHeaderRow(columns, csvContent);
        addDataRows(columns, component.getTableSortOrder(), componentData, csvContent);
        return csvContent;
    }

    private void addHeading(TableReportComponent component, ComponentData componentData, List<List<String>> content) {
        if (component.isShowTableHeading() && component.getTableHeading() != null) {
            Heading tableHeading = component.getTableHeading();
            String headingText = ThymeleafUtil.renderFromHtmlString(tableHeading.getText(), componentData.getVariables());
            content.add(List.of(headingText));
        }
    }

    private void addHeaderRow(List<DataKey> columns, List<List<String>> content) {
        content.add(columns.stream().map(DataKey::getLabel).collect(Collectors.toList()));
    }

    private void addDataRows(List<DataKey> columns, TableSortOrder tableSortOrder, ComponentData componentData, List<List<String>> content) {
        List<Map<String, String>> entityDatas = componentData.getEntityDatas();
        ReportUtils.sortRowsByTableSortOrder(entityDatas, tableSortOrder);
        for (Map<String, String> row : entityDatas) {
            content.add(columns.stream()
                    .map(col -> ReportUtils.formatValueWithPrecisionAndUnits(row.get(col.getLabel()), col))
                    .collect(Collectors.toList()));
        }
    }

    protected List<DataKey> getColumns(C component, DataSource dataSource) {
        return dataSource.getDataKeys();
    }

}
