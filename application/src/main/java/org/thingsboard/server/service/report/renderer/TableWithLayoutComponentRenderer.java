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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.report.configuration.CellSettings;
import org.thingsboard.server.common.data.report.configuration.ColumnSettings;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataKeySettings;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.common.data.report.configuration.TableSortOrder;
import org.thingsboard.server.common.data.report.configuration.components.TableWithLayoutReportComponent;
import org.thingsboard.server.common.data.report.configuration.style.Font;
import org.thingsboard.server.common.data.report.configuration.style.FontStyle;
import org.thingsboard.server.common.data.report.configuration.style.FontWeight;
import org.thingsboard.server.common.data.report.configuration.style.Heading;
import org.thingsboard.server.common.data.report.configuration.style.TextAlignment;
import org.thingsboard.server.common.data.report.configuration.style.VerticalAlignment;
import org.thingsboard.server.service.report.context.ComponentData;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.report.util.ColorUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base for the three table renderers (ENTITY / ALARM / TIME_SERIES) — PE {@code
 * report.renderer.TableWithLayoutComponentRenderer}. Pure presentation: it turns the rows the shared data
 * layer already resolved ({@link ComponentData#getEntityDatas()}) plus the component's column config into the
 * {@code html/components/table-template} fragment, then the layout base ({@link
 * ReportComponentWithLayoutRenderer}) wraps it. It NEVER fetches data itself — the F2 builders
 * ({@code buildTsComponentData}/{@code buildAlarmComponentData}/{@code collectEntityDatas}) do all
 * permission-scoped querying and hand the rows in.
 * <p>
 * <b>Column resolution.</b> {@link #getColumns} = the source's {@code dataKeys} + {@code latestDataKeys};
 * headers and per-cell style come from each {@link DataKey}'s {@link ColumnSettings} (all <b>config</b>-derived
 * and colour-normalised). Cell <b>values</b> are the resolved data strings, and the template escapes every one
 * of them ({@code th:text}, never {@code th:utext}) so a telemetry/alarm value that contains HTML/CSS
 * metacharacters renders as literal text, not markup — the table's marquee injection defence. Subclasses may
 * only steer cell <i>value/style defaults</i> through the {@code default*} hooks (alarm severity colour, status
 * display text, timestamp column), never inject raw markup.
 *
 * @param <C> the concrete table component type
 */
public abstract class TableWithLayoutComponentRenderer<C extends TableWithLayoutReportComponent>
        extends ReportComponentWithLayoutRenderer<C> {

    protected String dataSourceName() {
        return "data source";
    }

    protected String noDataMessage() {
        return "Table content is empty";
    }

    @Override
    protected String renderContent(C component, ComponentData componentData) {
        Optional<DataSource> dataSource = ReportUtils.getSingleDataSource(component);
        if (dataSource.isEmpty()) {
            return renderError("No " + dataSourceName() + " is configured for " + getType()
                    + " component. Please check the " + dataSourceName() + " configuration.");
        }
        List<DataKey> columns = getColumns(component, dataSource.get());
        if (columns.isEmpty()) {
            return renderError("No columns are configured for " + getType()
                    + " component. Please check the " + dataSourceName() + " configuration.");
        }
        Map<String, CellVariables> headers = buildCellVariables(columns, true);
        List<LinkedHashMap<String, CellVariables>> rows = buildDataRows(columns, component.getTableSortOrder(), componentData);
        Map<String, Object> componentVars = new HashMap<>();
        componentVars.put("columns", headers);
        componentVars.put("rows", rows);
        componentVars.put("noDataMessage", noDataMessage());
        if (component.isShowTableHeading() && component.getTableHeading() != null) {
            componentVars.put("showTableHeading", true);
            populateHeadingVariables(component, componentData, componentVars);
        } else {
            componentVars.put("showTableHeading", false);
        }
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/table-template", componentVars);
    }

    private String renderError(String errorMessage) {
        return ThymeleafUtil.renderFromHtmlTemplate("html/components/error-template", Map.of("errorMessage", errorMessage));
    }

    protected List<DataKey> getColumns(C component, DataSource dataSource) {
        LinkedList<DataKey> dataKeys = new LinkedList<>();
        Optional.ofNullable(dataSource.getDataKeys()).ifPresent(dataKeys::addAll);
        Optional.ofNullable(dataSource.getLatestDataKeys()).ifPresent(dataKeys::addAll);
        return dataKeys;
    }

    private List<LinkedHashMap<String, CellVariables>> buildDataRows(List<DataKey> columns, TableSortOrder tableSortOrder, ComponentData reportDataSource) {
        List<LinkedHashMap<String, CellVariables>> rows = new ArrayList<>();
        Map<String, CellVariables> cellDefaults = buildCellVariables(columns, false);
        List<Map<String, String>> entityDatas = reportDataSource.getEntityDatas();
        if (entityDatas == null) {
            return rows;
        }
        ReportUtils.sortRowsByTableSortOrder(entityDatas, tableSortOrder);
        for (Map<String, String> entityData : entityDatas) {
            LinkedHashMap<String, CellVariables> row = new LinkedHashMap<>();
            for (DataKey dataKey : columns) {
                String label = dataKey.getLabel();
                String key = dataKey.getName();
                CellVariables base = cellDefaults.get(label);
                String value = entityData.get(label);
                CellVariables variables = base.toBuilder()
                        .fontSize(formatFontSize(key, base.getFontSize()))
                        .fontWeight(formatFontWeight(key, base.getFontWeight()))
                        .color(formatColor(key, value, base.getColor()))
                        .value(formatValue(key, value, dataKey))
                        .build();
                row.put(label, variables);
            }
            rows.add(row);
        }
        return rows;
    }

    private void populateHeadingVariables(C component, ComponentData componentData, Map<String, Object> vars) {
        Heading heading = component.getTableHeading();
        // Same trust model as the HEADING/RICH_TEXT component: config markup is the author's, and any ${var}
        // interpolated from the resolved data is HTML-escaped by renderFromHtmlString (data-only, no OGNL).
        String headingText = ThymeleafUtil.renderFromHtmlString(heading.getText(), componentData.getVariables());
        Font font = getHeadingFont(heading);
        vars.put("headingText", headingText);
        vars.put("headingColor", ColorUtils.normalizeCssColorOrDefault(heading.getColor(), "#000"));
        vars.put("headingFontSize", Optional.ofNullable(font.getSize()).filter(s -> s > 0.0f).orElse(10.0f));
        vars.put("headingFontWeight", Optional.ofNullable(font.getWeight()).orElse(FontWeight.NORMAL).getValue());
        vars.put("headingFontStyle", Optional.ofNullable(font.getStyle()).orElse(FontStyle.NORMAL).getValue());
        vars.put("headingFontFamily", StringUtils.defaultString(font.getFamily(), "Roboto"));
        vars.put("headingTextAlignment", Optional.ofNullable(heading.getTextAlignment()).orElse(TextAlignment.CENTER).getValue());
        vars.put("headingVerticalAlignment", Optional.ofNullable(heading.getVerticalAlignment()).orElse(VerticalAlignment.MIDDLE).getValue());
        vars.put("headingHeight", heading.getHeight() != null && heading.getHeight() > 0 ? heading.getHeight() + "pt" : "100%");
    }

    private Float formatFontSize(String key, Float fontSize) {
        return fontSize != null ? fontSize : defaultFontSize(key);
    }

    private String formatFontWeight(String key, String fontWeight) {
        return fontWeight != null ? fontWeight : defaultFontWeight(key);
    }

    private String formatColor(String key, String value, String defaultColor) {
        return defaultColor != null ? defaultColor : defaultColor(key, value);
    }

    private String formatValue(String key, String value, DataKey dataKey) {
        if (value == null || value.isBlank()) {
            return "";
        }
        value = defaultValue(key, value);
        if (dataKey != null && (dataKey.getDecimals() != null || dataKey.getUnits() != null)) {
            value = ReportUtils.formatValueWithPrecisionAndUnits(value, dataKey);
        }
        return value;
    }

    protected Float defaultFontSize(String key) {
        return ReportUtils.ENTITY_TIME_FIELDS.contains(key) ? 9.0f : null;
    }

    protected String defaultFontWeight(String key) {
        return null;
    }

    protected String defaultColor(String key, String value) {
        return null;
    }

    protected String defaultValue(String key, String value) {
        return value;
    }

    protected Map<String, CellVariables> buildCellVariables(List<DataKey> columns, boolean isHeader) {
        LinkedHashMap<String, CellVariables> result = new LinkedHashMap<>();
        for (DataKey dataKey : columns) {
            result.put(dataKey.getLabel(), toCellVariables(dataKey, isHeader));
        }
        return result;
    }

    protected CellVariables toCellVariables(DataKey dataKey, boolean isHeader) {
        DataKeySettings settings = dataKey.getSettings();
        ColumnSettings columnSettings = settings instanceof ColumnSettings cs ? cs : null;
        return toCellVariables(dataKey.getName(), columnSettings, isHeader);
    }

    protected CellVariables toCellVariables(String key, ColumnSettings columnSettings, boolean isHeader) {
        if (columnSettings == null) {
            return new CellVariables(key);
        }
        CellSettings cellSettings = isHeader ? columnSettings.getHeader() : columnSettings.getCell();
        CellVariables cellVariables;
        if (cellSettings != null) {
            Font font = cellSettings.getFont();
            cellVariables = CellVariables.builder()
                    .key(key)
                    .color(cellSettings.getColor() != null ? ColorUtils.normalizeCssColor(cellSettings.getColor()) : null)
                    .backgroundColor(cellSettings.getBackgroundColor() != null ? ColorUtils.normalizeCssColor(cellSettings.getBackgroundColor()) : null)
                    .fontSize(font != null && font.getSize() != null && font.getSize() > 0.0f ? font.getSize() : null)
                    .fontWeight(font != null && font.getWeight() != null ? font.getWeight().name() : null)
                    .fontStyle(font != null && font.getStyle() != null ? font.getStyle().name() : null)
                    .fontFamily(font != null && font.getFamily() != null && !font.getFamily().isEmpty() ? font.getFamily() : null)
                    .textAlignment(cellSettings.getTextAlignment() != null ? cellSettings.getTextAlignment().name() : null)
                    .verticalAlignment(cellSettings.getVerticalAlignment() != null ? cellSettings.getVerticalAlignment().name() : null)
                    .build();
        } else {
            cellVariables = new CellVariables(key);
        }
        if (isHeader && !StringUtils.isBlank(columnSettings.getColumnWidth())) {
            cellVariables.setWidth(columnSettings.getColumnWidth());
        }
        return cellVariables;
    }

    private Font getHeadingFont(Heading tableHeading) {
        Font headingFont = tableHeading.getFont();
        if (headingFont == null) {
            headingFont = new Font();
            headingFont.setSize(20.0f);
            headingFont.setFamily("Roboto");
            headingFont.setStyle(FontStyle.NORMAL);
            headingFont.setWeight(FontWeight.NORMAL);
        }
        return headingFont;
    }

    /**
     * Per-cell template payload (PE {@code TableWithLayoutComponentRenderer.CellVariables}). {@code value} is
     * the resolved, escaped-at-render data string; the remaining fields are config-derived style attributes
     * (colour already normalised, alignments/weights enum-named). {@code public} so the standalone (non-Spring)
     * Thymeleaf OGNL evaluator can read its getters from the template — PE keeps it package-private, but PE's
     * CellVariables and the template live in the same module; CE widens visibility to keep OGNL reflection
     * happy. It carries no data-derived markup, so widening is purely a reflection concern.
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CellVariables {
        private String key;
        private String value;
        private String width;
        private String color;
        private String backgroundColor;
        private Float fontSize;
        private String fontWeight;
        private String fontStyle;
        private String fontFamily;
        private String textAlignment;
        private String verticalAlignment;

        public CellVariables(String key) {
            this.key = key;
        }
    }
}
