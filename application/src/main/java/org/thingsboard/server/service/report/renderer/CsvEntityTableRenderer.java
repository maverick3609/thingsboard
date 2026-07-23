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

import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.report.configuration.components.EntityTableComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;

/**
 * Renders an {@code ENTITY_TABLE} to CSV (PE {@code report.renderer.CsvEntityTableRenderer}): one row per
 * permission-scoped entity, columns from the source's data keys — all layout is the {@link
 * AbstractCsvComponentRenderer} base. A plain {@code @Component} (like the PDF table renderers): stateless,
 * injects nothing, so it boots renderer-off and is only collected by the gated {@code CsvReportService}.
 */
@Component
public class CsvEntityTableRenderer extends AbstractCsvComponentRenderer<EntityTableComponent> {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.ENTITY_TABLE;
    }

}
