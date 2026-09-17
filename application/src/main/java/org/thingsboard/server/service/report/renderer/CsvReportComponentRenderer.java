// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
