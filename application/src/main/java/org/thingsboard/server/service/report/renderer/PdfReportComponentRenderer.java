// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.renderer;

import org.thingsboard.server.common.data.report.configuration.components.ReportComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.service.report.context.ComponentData;

/**
 * Renders one typed {@link ReportComponent} to an HTML fragment (PE {@code report.renderer
 * .PdfReportComponentRenderer}). The engine ({@code PdfReportService}) keeps a registry keyed by {@link
 * #getType()} and dispatches each component in a template to the matching renderer, then assembles the
 * fragments into the page template. Every renderer is a stateless Spring {@code @Component} auto-collected
 * into that registry.
 *
 * @param <C> the concrete component type this renderer accepts
 */
public interface PdfReportComponentRenderer<C extends ReportComponent> {

    String render(C component, ComponentData componentData);

    ReportComponentType getType();

}
