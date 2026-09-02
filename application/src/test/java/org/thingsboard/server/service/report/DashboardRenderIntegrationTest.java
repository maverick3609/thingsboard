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
package org.thingsboard.server.service.report;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.service.report.render.PlaywrightWebReportRenderer;
import org.thingsboard.server.service.report.render.RenderOptions;
import org.thingsboard.server.service.report.render.ReportRenderException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Task 13: {@link PlaywrightWebReportRenderer#renderDashboard} end to end.
 * <p>
 * <b>Primary proof (runs headless, no platform needed):</b> a tiny local {@link HttpServer} serves
 * fixture HTML pages that mock the real {@code reportView} contract report.service.ts implements
 * (design spec §6.3) — poll for {@code window.postWebReportResult}, ping it bare once ready, then
 * on an {@code openReport} postMessage reply with the real {@code {success, pageHeight, error?}}
 * result. This proves the renderer's expose→navigate→await-ready→send-openReport→await-result→
 * size→capture mechanics against the actual protocol shape, without a running ThingsBoard. The
 * fixture variant is selected by {@code dashboardId} ({@code fixture-success} /
 * {@code fixture-failure} / {@code fixture-timeout}), which is also the real protocol's own
 * dashboard-identifying URL segment — no test-only renderer hook.
 * <p>
 * <b>Secondary proof (gated):</b> {@link #realDashboardRendersWhenPlatformConfigured()} drives the
 * same renderer against a real, seeded ThingsBoard dashboard when {@code TB_TEST_BASE_URL} /
 * {@code TB_TEST_DASHBOARD_ID} / {@code TB_TEST_ACCESS_TOKEN} are set; skips otherwise. Documents
 * the real-platform verification path for Task 27's operator E2E pass.
 * <p>
 * {@code @Tag("integration")} per the Task 13 brief (this class launches real headless Chromium
 * instances via {@link PlaywrightWebReportRenderer#init()}, not a plain in-JVM unit test); inert
 * today — the {@code application} module's Surefire config has no {@code includedGroups}/
 * {@code excludedGroups} filter yet, so this tag doesn't change which tests run until one is added.
 */
@Tag("integration")
class DashboardRenderIntegrationTest {

    /**
     * Generous relative to how fast a local static-HTML fixture actually resolves (well under a
     * second), but this value is also what the deliberately-never-ready fixture test waits out in
     * full, so it's kept well below JUnit/Surefire's own default timeouts rather than reused from
     * a production default (that default is 120000 ms, spec §13 — far too slow for a test suite).
     */
    private static final long TEST_TIMEOUT_MS = 6000;

    /**
     * Mirrors report.service.ts's own {@code installReportListener}/{@code handleMessage} shape
     * (design spec §6.3): poll every 20 ms for {@code window.postWebReportResult} and ping it bare
     * once found (the "ready" signal), then on an {@code openReport} postMessage — after a short
     * delay standing in for real widget-load settling — call it again with the real result. {@code %s}
     * is the per-variant result-call body (success/failure).
     */
    private static final String READY_POLL_AND_HANDLER_SCRIPT =
            "<script>" +
                    "function twbrSignalReady(){" +
                    "  if (window.postWebReportResult) { window.postWebReportResult({success:true}); }" +
                    "  else { setTimeout(twbrSignalReady, 20); }" +
                    "}" +
                    "window.addEventListener('message', function(event){" +
                    "  var msg;" +
                    "  try { msg = typeof event.data === 'string' ? JSON.parse(event.data) : event.data; }" +
                    "  catch (e) { return; }" +
                    "  if (msg && msg.type === 'openReport') { %s }" +
                    "});" +
                    "twbrSignalReady();" +
                    "</script>";

    private static final Map<String, String> FIXTURES = Map.of(
            "fixture-success", fixturePage(String.format(READY_POLL_AND_HANDLER_SCRIPT,
                    "setTimeout(function(){ window.postWebReportResult({success:true, pageHeight:600}); }, 50);")),
            "fixture-failure", fixturePage(String.format(READY_POLL_AND_HANDLER_SCRIPT,
                    "setTimeout(function(){ window.postWebReportResult({success:false, error:'Wait for report page timed out!'}); }, 50);")),
            "fixture-timeout", fixturePage("<!-- never calls postWebReportResult, not even the ready ping -->"),
            // FIX 4 regression fixtures: a success reply missing pageHeight, and a failure reply
            // whose error is a nested object rather than a string (Playwright deserializes a JS
            // object arg as a nested Map) — both used to risk a raw NPE/ClassCastException out of
            // PlaywrightWebReportRenderer's unchecked result-parsing casts.
            "fixture-missing-pageheight", fixturePage(String.format(READY_POLL_AND_HANDLER_SCRIPT,
                    "setTimeout(function(){ window.postWebReportResult({success:true}); }, 50);")),
            "fixture-nonstring-error", fixturePage(String.format(READY_POLL_AND_HANDLER_SCRIPT,
                    "setTimeout(function(){ window.postWebReportResult({success:false, error:{code:42,reason:'nested'}}); }, 50);"))
    );

    private static String fixturePage(String bodyScript) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>reportView fixture</title></head>"
                + "<body><div id=\"gridster-child\" style=\"height:600px;\"></div>" + bodyScript + "</body></html>";
    }

    static PlaywrightWebReportRenderer renderer;
    static HttpServer fixtureServer;
    static int fixturePort;

    @BeforeAll
    static void up() throws IOException {
        renderer = new PlaywrightWebReportRenderer(2, TEST_TIMEOUT_MS, "", 0L);
        renderer.init();

        fixtureServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fixtureServer.createContext("/dashboards/", DashboardRenderIntegrationTest::serveFixture);
        fixtureServer.start();
        fixturePort = fixtureServer.getAddress().getPort();
    }

    @AfterAll
    static void down() {
        if (renderer != null) {
            renderer.destroy();
        }
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
    }

    private static void serveFixture(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String variant = path.substring(path.lastIndexOf('/') + 1);
        String html = FIXTURES.getOrDefault(variant, FIXTURES.get("fixture-success"));
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String baseUrl() {
        return "http://localhost:" + fixturePort;
    }

    @Test
    void openReportSuccessHandshakeRendersNonEmptyPdf() {
        RenderOptions options = RenderOptions.builder()
                .dashboardId("fixture-success")
                .type("pdf")
                .namePattern("fixture-report-%d{yyyy-MM-dd}")
                .timezone("UTC")
                .build();

        ReportData result = renderer.renderDashboard(baseUrl(), "test-access-token", options);

        assertThat(result.getData()).isNotEmpty();
        assertThat(new String(result.getData(), 0, 5)).isEqualTo("%PDF-");
        assertThat(result.getContentType()).isEqualTo("application/pdf");
        assertThat(result.getName()).startsWith("fixture-report-");
    }

    @Test
    void openReportFailureSurfacesVerbatimErrorAsReportRenderException() {
        RenderOptions options = RenderOptions.builder().dashboardId("fixture-failure").type("pdf").build();

        assertThatThrownBy(() -> renderer.renderDashboard(baseUrl(), "test-access-token", options))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("Wait for report page timed out!");
    }

    @Test
    void neverReadyFixtureTimesOutAsReportRenderException() {
        RenderOptions options = RenderOptions.builder().dashboardId("fixture-timeout").type("pdf").build();

        assertThatThrownBy(() -> renderer.renderDashboard(baseUrl(), "test-access-token", options))
                .isInstanceOf(ReportRenderException.class);
    }

    /**
     * FIX 4 regression: before the guard, a success reply with no {@code pageHeight} key drove
     * {@code ((Number) result.get("pageHeight")).intValue()} on a {@code null}, i.e. a raw
     * {@link NullPointerException} escaping instead of this renderer's own
     * {@link ReportRenderException} contract.
     */
    @Test
    void missingPageHeightSurfacesAsReportRenderExceptionNotNpe() {
        RenderOptions options = RenderOptions.builder().dashboardId("fixture-missing-pageheight").type("pdf").build();

        assertThatThrownBy(() -> renderer.renderDashboard(baseUrl(), "test-access-token", options))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("pageHeight");
    }

    /**
     * FIX 4 regression: {@code error} isn't always a string — Playwright deserializes a JS object
     * argument as a nested {@code Map}, which this test's fixture sends deliberately. Must still
     * surface as a clean {@link ReportRenderException} message, not throw out of the
     * failure-reporting path itself.
     */
    @Test
    void nonStringErrorSurfacesAsReportRenderExceptionNotCce() {
        RenderOptions options = RenderOptions.builder().dashboardId("fixture-nonstring-error").type("pdf").build();

        assertThatThrownBy(() -> renderer.renderDashboard(baseUrl(), "test-access-token", options))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("Dashboard report render failed");
    }

    /**
     * Gated real-dashboard proof (Task 27 operator E2E path). Skips via
     * {@link Assumptions#assumeTrue} when the platform isn't configured — never fails CI, never
     * fails a local run without a seeded dashboard.
     */
    @Test
    void realDashboardRendersWhenPlatformConfigured() {
        String baseUrl = System.getenv("TB_TEST_BASE_URL");
        String dashboardId = System.getenv("TB_TEST_DASHBOARD_ID");
        String accessToken = System.getenv("TB_TEST_ACCESS_TOKEN");
        Assumptions.assumeTrue(isNotBlank(baseUrl) && isNotBlank(dashboardId) && isNotBlank(accessToken),
                "TB_TEST_BASE_URL / TB_TEST_DASHBOARD_ID / TB_TEST_ACCESS_TOKEN not set; " +
                        "skipping real-platform verification (see Task 27 operator E2E)");

        RenderOptions options = RenderOptions.builder().dashboardId(dashboardId).type("pdf").build();

        ReportData result = renderer.renderDashboard(baseUrl, accessToken, options);

        assertThat(result.getData()).isNotEmpty();
        assertThat(new String(result.getData(), 0, 5)).isEqualTo("%PDF-");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

}
