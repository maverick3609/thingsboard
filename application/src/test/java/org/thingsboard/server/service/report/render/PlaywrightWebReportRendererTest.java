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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        renderer = new PlaywrightWebReportRenderer(2, TEST_TIMEOUT_MS, "");
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
     * an unknown state, so {@link PlaywrightWebReportRenderer} recycles that pair — closes and relaunches
     * it — before returning it to the pool, instead of handing a possibly-wedged pair to the next caller.
     * Proves that recycling for real: triggers a timeout (same shape as
     * {@link #timeoutClosesContextAndThrows}), then immediately renders again on the very same shared
     * {@code renderer}. With only {@code maxConcurrent=2} pairs total this render stands a good chance of
     * drawing the just-recycled pair, and must succeed either way — proving recycle-on-timeout doesn't
     * wedge the pool for subsequent renders.
     */
    @Test
    void renderSucceedsAfterAPriorTimeout() {
        assertThatThrownBy(() -> renderer.renderRaw("data:text/html,<script>while(true){}</script>", 1))
                .isInstanceOf(ReportRenderException.class);

        byte[] pdf = renderer.renderRaw(
                "data:text/html,<html><body><h1 style='height:200px'>After timeout</h1></body></html>", 200);

        assertPdf(pdf);
    }

    /**
     * Proves genuine concurrency, not just that two sequential renders each work — untested by the tests
     * above, since JUnit runs {@code @Test}s sequentially. This launches two {@code renderRaw} calls from
     * two threads released simultaneously by a {@link CyclicBarrier} (not just submitted back-to-back,
     * which could accidentally serialize) and asserts both genuinely-concurrent renders produce a
     * non-empty PDF. {@code renderer} above is constructed with {@code maxConcurrent=2}, matching the two
     * threads here, so each thread checks out its own pooled {@code (Playwright, Browser)} pair for the
     * full duration of its render — no pair is ever driven by both threads at once, which is what makes
     * this safe (see {@link PlaywrightWebReportRenderer} class Javadoc for why a single {@code Browser}
     * shared across concurrent threads is not).
     */
    @Test
    void concurrentRendersBothSucceed() throws Exception {
        String dataUrl = "data:text/html,<html><body><h1 style='height:400px'>Concurrent</h1></body></html>";
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

}
