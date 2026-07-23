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

import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.common.data.report.configuration.components.TableReportComponent;
import org.thingsboard.server.service.report.context.ComponentData;

import java.util.List;

/**
 * One CSV renderer per tabular component type (PE {@code report.renderer.CsvReportComponentRenderer}) — the CSV
 * analogue of {@link PdfReportComponentRenderer}. Given a table component and the {@link ComponentData} the
 * shared server-side data layer ({@code AbstractReportService}, F2) already resolved for it, it returns that
 * component's rows as a {@code List<List<String>>} (a list of rows, each a list of cell strings). Only the
 * three tabular types have a CSV representation — {@code ENTITY_TABLE}, {@code ALARM_TABLE}, {@code
 * TIME_SERIES_TABLE} (see {@code CsvReportService}); non-tabular components have no CSV form and produce no
 * output.
 * <p>
 * The renderer does not query — all data comes from the permission-scoped {@code ComponentData} the engine
 * builds under {@code ctx.securityUser}. The {@code CsvReportService} concentrates cell escaping in {@code
 * CsvUtils.generateCsv} (which also applies the CSV formula-injection guard), so renderers emit plain values.
 */
public interface CsvReportComponentRenderer<C extends TableReportComponent> {

    List<List<String>> render(C component, ComponentData componentData);

    ReportComponentType getType();

}
