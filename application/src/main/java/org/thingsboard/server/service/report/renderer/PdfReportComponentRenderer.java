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
