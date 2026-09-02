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
package org.thingsboard.server.service.report.render;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Task 11: the in-process Playwright renderer core. Proves the render primitive itself —
 * a self-contained {@code data:} HTML URL becomes a non-empty PDF, a hard timeout force-closes
 * the in-flight {@link com.microsoft.playwright.BrowserContext} and surfaces as
 * {@link ReportRenderException}, a render after a timeout still succeeds (the timed-out pool slot
 * is recycled rather than left wedged), and two renders launched genuinely concurrently (via a
 * {@link CyclicBarrier}, not just back-to-back) both complete successfully against the renderer's
 * pool of thread-confined {@code (Playwright, Browser)} pairs — ahead of Task 13 wiring up the real
 * dashboard handshake.
 * <p>
 * Also covers a Task 13 review fix (see {@link #buildOpenReportMessageCarriesComputedTimeout()}
 * below): {@link PlaywrightWebReportRenderer#buildOpenReportMessage} is package-private
 * specifically so this class — already the home of this renderer's white-box unit coverage — can
 * assert its JSON output directly.
 */
class PlaywrightWebReportRendererTest {

    /**
     * Kept short (rather than the eventual production default) so the timeout test below doesn't
     * stall the suite: real dashboard renders are bounded by {@code reports.generation_timeout_ms}
     * (wired in Task 26), this just proves the renderer honors whatever bound it's given.
     */
    private static final long TEST_TIMEOUT_MS = 5000;

    static PlaywrightWebReportRenderer renderer;

    @BeforeAll
    static void up() {
        renderer = new PlaywrightWebReportRenderer(2, TEST_TIMEOUT_MS, "", 0L);
        renderer.init();
    }

    @AfterAll
    static void down() {
        if (renderer != null) {
            renderer.destroy();
        }
    }

    @Test
    void rendersDataUrlToNonEmptyPdf() {
        String dataUrl = "data:text/html,<html><body><h1 style='height:400px'>Hi</h1></body></html>";

        byte[] pdf = renderer.renderRaw(dataUrl, 500);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void timeoutClosesContextAndThrows() {
        assertThatThrownBy(() -> renderer.renderRaw("data:text/html,<script>while(true){}</script>", 1))
                .isInstanceOf(ReportRenderException.class);
    }

    /**
     * A timeout leaves its checked-out pooled {@code (Playwright, Browser)} pair's driver connection in
     * an unknown state, so {@code checkin} closes that pair and offers a "dead" placeholder back to the
     * pool in its place, instead of handing a possibly-wedged pair to the next caller; {@code checkout}
     * lazily relaunches a dead placeholder into a fresh pair whenever a caller next draws it. This test
     * triggers a timeout (same shape as {@link #timeoutClosesContextAndThrows}), renders once more to
     * prove the very next caller isn't wedged, then — since {@code ArrayBlockingQueue} is strict FIFO, so
     * exactly which pair either render draws is deterministic, not a matter of chance (unlike an earlier
     * version of this comment claimed) — finishes by driving {@code maxConcurrent} (2) genuinely
     * concurrent renders to prove the timeout didn't cost the pool a slot: if a slot had been lost, one of
     * these two barrier-synchronized renders would starve past its timeout instead of completing.
     */
    @Test
    void renderSucceedsAfterAPriorTimeout() throws Exception {
        assertThatThrownBy(() -> renderer.renderRaw("data:text/html,<script>while(true){}</script>", 1))
                .isInstanceOf(ReportRenderException.class);

        byte[] pdf = renderer.renderRaw(
                "data:text/html,<html><body><h1 style='height:200px'>After timeout</h1></body></html>", 200);
        assertPdf(pdf);

        // No slot lost to the timeout above: the pool must still serve `maxConcurrent` (2) genuinely
        // concurrent renders.
        assertTwoConcurrentRendersSucceed(
                "data:text/html,<html><body><h1 style='height:400px'>Concurrent after timeout</h1></body></html>");
    }

    /**
     * Proves genuine concurrency, not just that two sequential renders each work — untested by the tests
     * above, since JUnit runs {@code @Test}s sequentially. See {@link #assertTwoConcurrentRendersSucceed}
     * for the barrier-synchronized mechanics. {@code renderer} above is constructed with
     * {@code maxConcurrent=2}, matching the two threads driven there, so each thread checks out its own
     * pooled {@code (Playwright, Browser)} pair for the full duration of its render — no pair is ever
     * driven by both threads at once, which is what makes this safe (see
     * {@link PlaywrightWebReportRenderer} class Javadoc for why a single {@code Browser} shared across
     * concurrent threads is not).
     */
    @Test
    void concurrentRendersBothSucceed() throws Exception {
        assertTwoConcurrentRendersSucceed(
                "data:text/html,<html><body><h1 style='height:400px'>Concurrent</h1></body></html>");
    }

    /**
     * Drives {@code maxConcurrent} (2) {@code renderRaw} calls from two threads released simultaneously
     * by a {@link CyclicBarrier} (not just submitted back-to-back, which could accidentally serialize)
     * and asserts both produce a non-empty PDF within {@code TEST_TIMEOUT_MS * 2}. Shared by
     * {@link #concurrentRendersBothSucceed} (proving genuine concurrency works at all) and
     * {@link #renderSucceedsAfterAPriorTimeout} (proving it still works, with no slot lost, after an
     * earlier timeout).
     */
    private static void assertTwoConcurrentRendersSucceed(String dataUrl) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<byte[]> first = CompletableFuture.supplyAsync(
                    () -> renderConcurrently(startTogether, dataUrl), callers);
            CompletableFuture<byte[]> second = CompletableFuture.supplyAsync(
                    () -> renderConcurrently(startTogether, dataUrl), callers);

            CompletableFuture.allOf(first, second).get(TEST_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);

            assertPdf(first.join());
            assertPdf(second.join());
        } finally {
            callers.shutdownNow();
        }
    }

    private static byte[] renderConcurrently(CyclicBarrier startTogether, String dataUrl) {
        try {
            startTogether.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new RuntimeException(e);
        }
        return renderer.renderRaw(dataUrl, 500);
    }

    private static void assertPdf(byte[] pdf) {
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    /**
     * Task 13 review fix: before this fix, {@code buildOpenReportMessage} never set
     * {@code data.timeout} at all, so report.service.ts's {@code command.timeout} arrived
     * {@code undefined} and both UI readiness stages silently fell back to their own hardcoded
     * 3000 ms defaults, ignoring whatever {@code timeoutMs} this renderer was actually configured
     * with (production default 120000 ms, spec §13) — a real dashboard settling in, say, 10s would
     * then fail with a spurious "Wait for report page timed out!" well inside this renderer's real
     * budget. This asserts the sent payload carries a numeric {@code timeout} close to (not equal
     * to — a capture margin is trimmed off, see {@link PlaywrightWebReportRenderer#buildOpenReportMessage})
     * the configured {@code timeoutMs}: 20000 - 5000 = 15000, unambiguously distinguishable from
     * the UI's own hardcoded 3000 ms fallback (a regression here would either omit the key
     * entirely or leave it at a value with no relationship to the configured 20000).
     * <p>
     * Asserts against the parsed {@link JsonNode} (not a raw substring match) so this also catches
     * a would-be regression that serialized {@code timeout} as a JSON string instead of a number.
     * <p>
     * Chose this over a fixture-echo integration test (option (a) in the review): the integration
     * fixture's postMessage/JSON round trip already proves generically that arbitrary payload
     * fields survive intact (see {@code DashboardRenderIntegrationTest}'s existing 3 tests, e.g.
     * {@code accessToken}/{@code dashboardId}); a JS string-concat echo (e.g. {@code 'x:' + value})
     * can't even distinguish a numeric {@code 15000} from a same-looking string {@code "15000"}
     * (JS coerces either the same way), so it wouldn't be strictly more rigorous here — while this
     * direct assertion is both cheaper (no extra headless browser launch) and precise about type.
     */
    @Test
    void buildOpenReportMessageCarriesComputedTimeout() {
        RenderOptions options = RenderOptions.builder().dashboardId("dash-1").type("pdf").build();

        String json = PlaywrightWebReportRenderer.buildOpenReportMessage("test-token", options, 20000L);

        JsonNode data = JacksonUtil.toJsonNode(json).get("data");
        assertThat(data.get("timeout").isNumber()).isTrue();
        assertThat(data.get("timeout").asLong()).isEqualTo(15000L);
        // Unrelated fields still present/correct — this change only ever adds a key.
        assertThat(data.get("accessToken").asText()).isEqualTo("test-token");
        assertThat(data.get("dashboardId").asText()).isEqualTo("dash-1");
    }

    /**
     * The 3000 ms floor: a small configured {@code timeoutMs} (below the 5000 ms capture margin)
     * must not drive the UI-side timeout negative or to zero, which would starve every render
     * before the UI's own widgets could ever settle.
     */
    @Test
    void buildOpenReportMessageFloorsTimeoutAt3000ForASmallConfiguredTimeout() {
        RenderOptions options = RenderOptions.builder().dashboardId("dash-1").type("pdf").build();

        String json = PlaywrightWebReportRenderer.buildOpenReportMessage("test-token", options, 4000L);

        assertThat(JacksonUtil.toJsonNode(json).get("data").get("timeout").asLong()).isEqualTo(3000L);
    }

}
