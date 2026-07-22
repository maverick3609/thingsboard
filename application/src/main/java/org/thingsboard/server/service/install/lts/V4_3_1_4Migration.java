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
package org.thingsboard.server.service.install.lts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reporting R2a — one-time data migration that rewrites pre-existing R1-era {@code report_template.configuration}
 * rows from R1's flat shape to PE-shape (Decision D1). The jsonb column is unchanged; only the document contents move:
 * <ul>
 *   <li><b>Root discriminator</b>: R1 keyed the root on {@code type:"PDF"}; PE keys it on {@code format} with no
 *       {@code defaultImpl}. This renames a format-valued root {@code type} to {@code format} (default {@code "PDF"}
 *       when neither is present).</li>
 *   <li><b>DASHBOARD nesting</b>: R1 wrote {@code {"type":"DASHBOARD","dashboardId":…,"state":…,"timewindow":…,"pageWidth":…}}
 *       flat; PE nests those under a {@code config} object and has no {@code pageWidth}. This moves
 *       {@code dashboardId}/{@code state}/{@code timewindow} into a nested {@code config} and drops {@code pageWidth}.</li>
 * </ul>
 * Idempotent: a row already in PE-shape (root has {@code format} and every DASHBOARD component already has {@code config})
 * is left untouched — only genuinely changed rows are written back. Operates on raw {@link JsonNode}s (not the typed
 * config model) so a malformed legacy row cannot NPE a typed binder.
 * <p>
 * There is no DDL in R2a; {@code data/upgrade/lts/4.3.1.4/schema_update.sql} is a header-only no-op that exists only to
 * satisfy the dir↔bean consistency guard in {@code LtsMigrationIntegrationTest}.
 */
@Slf4j
@Component
@TbCoreComponent
@RequiredArgsConstructor
public class V4_3_1_4Migration implements LtsMigration {

    private static final String DASHBOARD_TYPE = "DASHBOARD";
    private static final List<String> DASHBOARD_FLAT_KEYS = List.of("dashboardId", "state", "timewindow");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String getVersion() {
        return "4.3.1.4";
    }

    @Override
    public void apply() {
        int rewritten = migrateReportTemplateConfigs();
        log.info("R2a: rewrote {} report_template configuration row(s) to PE-shape", rewritten);
    }

    /** A report_template row's id and its configuration jsonb as text (null configs excluded by the query). */
    private record Row(UUID id, String configuration) {}

    /**
     * Rewrites every out-of-shape report_template.configuration row to PE-shape and returns the number of rows changed.
     * Package-private so tests can assert the changed count directly (the {@link LtsMigration} contract is void).
     */
    int migrateReportTemplateConfigs() {
        List<Row> rows = jdbcTemplate.query(
                "SELECT id, configuration::text AS configuration FROM report_template WHERE configuration IS NOT NULL",
                (rs, i) -> new Row((UUID) rs.getObject("id"), rs.getString("configuration")));

        int rewritten = 0;
        for (Row row : rows) {
            JsonNode root;
            try {
                root = JacksonUtil.toJsonNode(row.configuration());
            } catch (Exception e) {
                log.warn("R2a: skipping report_template {} — configuration is not valid JSON", row.id(), e);
                continue;
            }
            if (root == null || !root.isObject()) {
                log.debug("R2a: skipping report_template {} — configuration root is not an object", row.id());
                continue;
            }
            boolean changed = toPeShape((ObjectNode) root);
            if (changed) {
                jdbcTemplate.update("UPDATE report_template SET configuration = ?::jsonb WHERE id = ?",
                        JacksonUtil.toString(root), row.id());
                rewritten++;
                log.debug("R2a: rewrote report_template {} configuration to PE-shape", row.id());
            }
        }
        return rewritten;
    }

    /** Mutates the config root in place; returns true if anything changed. */
    private boolean toPeShape(ObjectNode root) {
        boolean changed = migrateRootFormat(root);
        JsonNode components = root.get("components");
        if (components instanceof ArrayNode array) {
            for (JsonNode component : array) {
                if (component instanceof ObjectNode obj
                        && DASHBOARD_TYPE.equals(obj.path("type").asText())
                        && migrateDashboardComponent(obj)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    // R1 root: {"type":"PDF",...} → PE root: {"format":"PDF",...}. Rename only a format-valued type; default PDF.
    private boolean migrateRootFormat(ObjectNode root) {
        if (root.hasNonNull("format")) {
            return false; // already PE-shape root
        }
        JsonNode type = root.get("type");
        if (type != null && ("PDF".equals(type.asText()) || "CSV".equals(type.asText()))) {
            root.put("format", type.asText());
            root.remove("type");
        } else {
            root.put("format", "PDF");
        }
        return true;
    }

    // R1 flat DASHBOARD → PE nested: move dashboardId/state/timewindow under config, drop pageWidth.
    private boolean migrateDashboardComponent(ObjectNode component) {
        if (component.has("config")) {
            return false; // already nested
        }
        ObjectNode config = JacksonUtil.newObjectNode();
        List<String> moved = new ArrayList<>();
        for (String key : DASHBOARD_FLAT_KEYS) {
            if (component.has(key)) {
                config.set(key, component.remove(key));
                moved.add(key);
            }
        }
        boolean hadPageWidth = component.remove("pageWidth") != null;
        if (moved.isEmpty() && !hadPageWidth) {
            // Nothing flat to move and no stray pageWidth: not a flat R1 DASHBOARD, leave as-is.
            return false;
        }
        component.set("config", config);
        return true;
    }
}
