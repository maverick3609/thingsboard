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
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@DaoSqlTest
public class ReportTemplateConfigMigrationTest extends AbstractControllerTest {

    @Autowired
    private V4_3_1_4Migration migration;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> seededIds = new ArrayList<>();

    @After
    public void tearDown() {
        for (UUID id : seededIds) {
            jdbcTemplate.update("DELETE FROM report_template WHERE id = ?", id);
        }
        seededIds.clear();
    }

    // ---- R1 flat shape ----------------------------------------------------

    private static final String DASHBOARD_ID = "11111111-1111-1111-1111-111111111111";

    private String flatR1Config() {
        return "{"
                + "\"type\":\"PDF\","
                + "\"namePattern\":\"Report %d\","
                + "\"components\":[{"
                + "\"type\":\"DASHBOARD\","
                + "\"dashboardId\":\"" + DASHBOARD_ID + "\","
                + "\"state\":\"default\","
                + "\"timewindow\":{\"realtime\":{\"timewindowMs\":86400000}},"
                + "\"pageWidth\":210"
                + "}]}";
    }

    private String peShapeConfig() {
        return "{"
                + "\"format\":\"PDF\","
                + "\"namePattern\":\"Report %d\","
                + "\"components\":[{"
                + "\"type\":\"DASHBOARD\","
                + "\"config\":{"
                + "\"dashboardId\":\"" + DASHBOARD_ID + "\","
                + "\"state\":\"default\","
                + "\"timewindow\":{\"realtime\":{\"timewindowMs\":86400000}}"
                + "}}]}";
    }

    private UUID seed(String configuration) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO report_template (id, created_time, tenant_id, name, format, type, configuration) "
                        + "VALUES (?, ?, ?, ?, 'PDF', 'REPORT', ?::jsonb)",
                id, System.currentTimeMillis(), TenantId.SYS_TENANT_ID.getId(),
                "tmpl-" + id, configuration);
        seededIds.add(id);
        return id;
    }

    private JsonNode configOf(UUID id) {
        return JacksonUtil.toJsonNode(jdbcTemplate.queryForObject(
                "SELECT configuration::text FROM report_template WHERE id = ?", String.class, id));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    public void rewritesFlatR1RowToPeShape() {
        UUID id = seed(flatR1Config());

        int rewritten = migration.migrateReportTemplateConfigs();

        assertTrue("at least the seeded flat row is rewritten", rewritten >= 1);

        JsonNode cfg = configOf(id);
        // root keyed on format, no stray type
        assertEquals("PDF", cfg.get("format").asText());
        assertFalse("root type dropped", cfg.has("type"));
        assertEquals("Report %d", cfg.get("namePattern").asText());

        JsonNode dashboard = cfg.get("components").get(0);
        assertEquals("DASHBOARD", dashboard.get("type").asText());
        // flat keys moved under a nested config
        assertFalse("flat dashboardId removed", dashboard.has("dashboardId"));
        assertFalse("flat state removed", dashboard.has("state"));
        assertFalse("flat timewindow removed", dashboard.has("timewindow"));
        assertFalse("pageWidth dropped (not in typed model)", dashboard.has("pageWidth"));

        JsonNode nested = dashboard.get("config");
        assertEquals(DASHBOARD_ID, nested.get("dashboardId").asText());
        assertEquals("default", nested.get("state").asText());
        assertEquals(86400000L, nested.get("timewindow").get("realtime").get("timewindowMs").asLong());
    }

    @Test
    public void secondRunIsNoOpForAlreadyMigratedRow() {
        seed(flatR1Config());

        int first = migration.migrateReportTemplateConfigs();
        assertTrue(first >= 1);

        // Re-run over the now-migrated data: the seeded row must not be rewritten again.
        // (Assert on a fresh flat row's absence rather than the global count, so a concurrent
        //  test's rows can't skew this — we prove idempotency by re-migrating a PE-shape row below.)
        UUID peId = seed(peShapeConfig());
        String before = jdbcTemplate.queryForObject(
                "SELECT configuration::text FROM report_template WHERE id = ?", String.class, peId);

        int second = migration.migrateReportTemplateConfigs();
        assertEquals("already-migrated + already-PE rows produce no rewrites", 0, second);

        String after = jdbcTemplate.queryForObject(
                "SELECT configuration::text FROM report_template WHERE id = ?", String.class, peId);
        assertEquals("already-PE-shape row left byte-identical", before, after);
    }

    @Test
    public void leavesAlreadyPeShapeRowUntouched() {
        UUID id = seed(peShapeConfig());
        JsonNode before = configOf(id);

        migration.migrateReportTemplateConfigs();

        assertEquals("PE-shape row unchanged", before, configOf(id));
    }

    @Test
    public void flipsRootFormatEvenWithoutDashboardComponent() {
        // Root still on R1's type:PDF, but the only component is non-DASHBOARD.
        UUID id = seed("{\"type\":\"PDF\",\"components\":[{\"type\":\"PAGE_BREAK\"}]}");

        int rewritten = migration.migrateReportTemplateConfigs();
        assertTrue(rewritten >= 1);

        JsonNode cfg = configOf(id);
        assertEquals("PDF", cfg.get("format").asText());
        assertFalse(cfg.has("type"));
        assertEquals("PAGE_BREAK", cfg.get("components").get(0).get("type").asText());
    }

    @Test
    public void defaultsFormatToPdfWhenRootHasNeitherFormatNorType() {
        UUID id = seed("{\"namePattern\":\"x\",\"components\":[]}");

        int rewritten = migration.migrateReportTemplateConfigs();
        assertTrue(rewritten >= 1);

        assertEquals("PDF", configOf(id).get("format").asText());
    }
}
