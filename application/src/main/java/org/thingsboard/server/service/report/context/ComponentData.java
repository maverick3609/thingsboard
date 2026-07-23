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
package org.thingsboard.server.service.report.context;

import lombok.Getter;
import lombok.Setter;
import org.thingsboard.server.common.data.report.configuration.DataKey;
import org.thingsboard.server.common.data.report.configuration.DataSource;
import org.thingsboard.server.service.report.util.ThymeleafUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-component render payload (PE {@code report.context.ComponentData}) — the second argument every
 * {@link org.thingsboard.server.service.report.renderer.PdfReportComponentRenderer} receives. Carries
 * the usable page width (so layout wrappers can size themselves), a text-interpolation {@code variables}
 * map, resolved {@code entityDatas} rows, an {@code image} (the DASHBOARD PNG), and an {@code error}
 * string (renders an error box instead of the component).
 * <p>
 * <b>R2b-F2.</b> The server-side data layer ({@link org.thingsboard.server.service.report.AbstractReportService})
 * builds instances through the {@link #ComponentData(int, DataSource, List, Map) DataSource-aware constructor}:
 * given a {@code DataSource}, each of its {@code DataKey}s' label→value is injected into {@code variables}
 * (so HEADING/RICH_TEXT can interpolate {@code ${label}}), and a {@code rowCount} is always exposed. R2a's
 * simpler constructors still serve the non-data renderers — {@code image} (DASHBOARD), an empty {@code
 * variables} map (HEADING/RICH_TEXT with no bound data), and {@code error} (fallbacks).
 */
@Getter
public class ComponentData {

    private final int usablePageWidthPx;
    /**
     * Inner content width (usable page width minus this component's horizontal margins + paddings, pt→px),
     * computed per-render by {@code ReportComponentWithLayoutRenderer#render} and read back by image
     * renderers. Lives here (not on the singleton renderer) so concurrent renders never cross-contaminate.
     */
    @Setter
    private int layoutWidthPx;
    @Setter
    private List<Map<String, String>> entityDatas;
    @Setter
    private Map<String, Object> variables;
    @Setter
    private byte[] image;
    @Setter
    private String error;

    public ComponentData(int usablePageWidthPx) {
        this.usablePageWidthPx = usablePageWidthPx;
        this.entityDatas = new ArrayList<>();
        this.variables = new HashMap<>();
    }

    public ComponentData(int usablePageWidthPx, String error) {
        this(usablePageWidthPx);
        this.error = error;
    }

    public ComponentData(int usablePageWidthPx, byte[] image) {
        this(usablePageWidthPx);
        this.image = image;
    }

    /**
     * PE-verbatim canonical constructor (R2b-F2). When {@code dataSource} is non-null, each of its data
     * keys' label→value is injected into {@code variables} from the resolved {@code entityDatas} rows (so a
     * HEADING/RICH_TEXT can interpolate {@code ${label}}); {@code rowCount} is always set to the row count.
     * The R2b table builders pass a {@code null} {@code dataSource} (their columns come from {@code
     * entityDatas} directly) — the DataSource-driven variable injection is exercised by the single-datasource
     * builds the PDF/CSV dispatchers add in Tasks G/I.
     */
    public ComponentData(int usablePageWidthPx, DataSource dataSource, List<Map<String, String>> entityDatas, Map<String, Object> variables) {
        this.usablePageWidthPx = usablePageWidthPx;
        this.entityDatas = entityDatas;
        this.variables = variables;
        if (dataSource != null) {
            injectVariablesFromEntityDatas(dataSource);
        }
        variables.put("rowCount", this.entityDatas.size());
    }

    private void injectVariablesFromEntityDatas(DataSource dataSource) {
        Map<String, DataKey> labelToDataKey = Stream.concat(
                        Optional.ofNullable(dataSource.getDataKeys()).orElse(Collections.emptyList()).stream(),
                        Optional.ofNullable(dataSource.getLatestDataKeys()).orElse(Collections.emptyList()).stream())
                .collect(Collectors.toMap(
                        dataKey -> ThymeleafUtil.normalizeVariableName(dataKey.getLabel()),
                        Function.identity(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new));
        for (Map.Entry<String, DataKey> entry : labelToDataKey.entrySet()) {
            for (Map<String, String> entityData : this.entityDatas) {
                this.variables.put(entry.getKey(), entityData.getOrDefault(entry.getValue().getLabel(), ""));
            }
        }
    }
}
