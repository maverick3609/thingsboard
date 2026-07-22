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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-component render payload (PE {@code report.context.ComponentData}) — the second argument every
 * {@link org.thingsboard.server.service.report.renderer.PdfReportComponentRenderer} receives. Carries
 * the usable page width (so layout wrappers can size themselves), a text-interpolation {@code variables}
 * map, resolved {@code entityDatas} rows, an {@code image} (the DASHBOARD PNG), and an {@code error}
 * string (renders an error box instead of the component).
 * <p>
 * <b>R2a-minimal.</b> PE's version additionally injects {@code variables}/{@code entityDatas} from a
 * {@code DataSource}'s {@code DataKey}s (the server-side data layer). That injection is <b>R2b</b>: R2a
 * populates only what the non-data renderers read — {@code image} (DASHBOARD), an empty {@code variables}
 * map (HEADING/RICH_TEXT have no server-side data yet), and {@code error} (fallbacks).
 */
@Getter
public class ComponentData {

    private final int usablePageWidthPx;
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
}
