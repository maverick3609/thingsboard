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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.report.ReportData;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-process report renderer built on Playwright-Java, replacing PE's external
 * {@code tb-web-report} microservice.
 * <p>
 * <b>Concurrency design — a thread-confined pool, not one shared {@link Browser}:</b> Playwright-Java's
 * sync client drives its {@link Playwright}/{@link Browser} connection with a single Node.js subprocess
 * and synchronous request/response correlation; that connection is not safe for concurrent calls issued
 * from multiple application threads at once. This was proven empirically, not assumed (see
 * {@code task-11-report.md}): two threads sharing one {@link Browser} and racing
 * {@link Browser#newContext()} corrupted the driver's internal {@code Connection}/{@code ChannelOwner}
 * object registry, with a different symptom every run (an "object doesn't exist" lookup failure, a
 * {@code NegativeArraySizeException}, a "cannot find object to call __adopt__" failure) — the signature
 * of a genuine data race, not a deterministic bug. Sequential reuse of one {@link Browser} across
 * renders is fine (proven by the non-concurrent tests in {@code PlaywrightWebReportRendererTest}); it's
 * genuinely-simultaneous access from two threads that isn't.
 * <p>
 * The fix: at {@link #init()}, launch {@code maxConcurrent} independent {@code (Playwright, Browser)}
 * pairs — each with its own driver subprocess and connection — and hold them in a {@link BlockingQueue}
 * ({@link #pool}). Each render {@link BlockingQueue#take() takes} a pair (this blocks once every pair is
 * checked out, which is what bounds concurrency now — there is no separate semaphore), drives a fresh
 * {@link BrowserContext}/{@link Page} on it, and {@link BlockingQueue#offer(Object) returns} the pair
 * when done. A given pair is therefore only ever driven by one thread at a time; different pairs, with
 * different connections, can be driven by different threads truly concurrently with no shared mutable
 * driver state between them.
 * <p>
 * Each render is wrapped in a {@link CompletableFuture} bounded by a hard timeout ({@code timeoutMs}) —
 * on timeout the in-flight context is force-closed (unblocking whatever Playwright call the render
 * thread is stuck in) and a {@link ReportRenderException} is thrown. Because a timed-out render leaves
 * its pair's driver connection in an unknown state, {@link #checkin} closes that pair and offers a
 * "dead" placeholder back to {@link #pool} in its place — {@link #pool} never shrinks, but a slot can
 * sit dead until it's next drawn. {@link #checkout} self-heals a dead (or otherwise
 * disconnected/corrupted) slot by lazily relaunching a fresh pair for whichever caller draws it next;
 * if that relaunch itself fails, a dead placeholder is offered back so the slot count is preserved and
 * the failure is scoped to that one caller. The checked-out pair is always returned to the pool,
 * success or failure.
 * <p>
 * Disabled by default ({@code reports.renderer.enabled}); wiring the real bean with config-sourced
 * constructor args lands in Task 26. Two render entry points share the pool above: {@link #renderRaw}
 * (Task 11 — an arbitrary URL, no protocol) and {@link #renderDashboard} (Task 13 — drives the PE
 * {@code openReport} handshake, design spec §6.3, against a real ThingsBoard dashboard). This class
 * is exercised directly (plain {@code new} + {@link #init()}/{@link #destroy()}) to prove both.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class PlaywrightWebReportRenderer {

    private final int maxConcurrent;
    private final long timeoutMs;
    private final String browserPath;

    private BlockingQueue<PooledBrowser> pool;
    private ExecutorService renderExecutor;

    public PlaywrightWebReportRenderer(
            @Value("${reports.renderer.max_concurrent:2}") int maxConcurrent,
            @Value("${reports.generation_timeout_ms:120000}") long timeoutMs,
            @Value("${reports.renderer.browser_path:}") String browserPath) {
        this.maxConcurrent = maxConcurrent;
        this.timeoutMs = timeoutMs;
        this.browserPath = browserPath;
    }

    @PostConstruct
    public void init() {
        log.info("Starting Playwright web report renderer, maxConcurrent [{}], timeoutMs [{}]", maxConcurrent, timeoutMs);
        BlockingQueue<PooledBrowser> created = new ArrayBlockingQueue<>(maxConcurrent);
        try {
            for (int i = 0; i < maxConcurrent; i++) {
                created.add(launchPooledBrowser());
            }
        } catch (RuntimeException e) {
            // Don't leak already-launched pairs if a later one in the loop fails to start.
            created.forEach(PlaywrightWebReportRenderer::closePooledBrowser);
            throw e;
        }
        this.pool = created;
        this.renderExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Stops accepting new renders, then gives in-flight ones a bounded window to finish on their own —
     * a render already past its {@code timeoutMs} bound is already unwinding via {@link #renderRaw}'s own
     * timeout handling (which force-closes its context and lets its {@code checkin} run), so
     * {@code timeoutMs} plus a short grace period is enough for every in-flight render's {@code checkin}
     * to return its pair to {@link #pool} before this method drains and closes it. Without this wait, a
     * render whose task hasn't reached its {@code checkin} yet would have its pair drained and closed out
     * from under it here, and its own later {@code checkin} would then return a closed pair to whichever
     * caller starts up next (there is none once shutdown completes, but this method itself must not race
     * a still-running render). Falls back to {@link ExecutorService#shutdownNow()} if the grace window
     * elapses.
     */
    @PreDestroy
    public void destroy() {
        log.info("Stopping Playwright web report renderer");
        if (renderExecutor != null) {
            renderExecutor.shutdown();
            long awaitMs = timeoutMs + TimeUnit.SECONDS.toMillis(5);
            try {
                if (!renderExecutor.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)) {
                    log.warn("Render executor did not terminate within {} ms of shutdown; forcing shutdown", awaitMs);
                    renderExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                renderExecutor.shutdownNow();
            }
        }
        if (pool != null) {
            PooledBrowser pb;
            while ((pb = pool.poll()) != null) {
                closePooledBrowser(pb);
            }
        }
    }

    /**
     * Renders {@code url} to a PDF: checks out a pooled {@code (Playwright, Browser)} pair (blocking if
     * every pair is currently checked out — this is what bounds concurrency), navigates a fresh,
     * isolated {@link BrowserContext}/{@link Page} on that pair to it, optionally settles for
     * {@code settleMs}, then snapshots {@link Page#pdf()}. Bounded by this renderer's {@code timeoutMs};
     * on timeout the context is force-closed and the pair is closed and replaced with a dead placeholder
     * (relaunched lazily on a later {@link #checkout}, see {@link #checkin}) rather than handed back
     * as-is, and a {@link ReportRenderException} is thrown instead of hanging the caller. Every failure
     * path — including context creation itself (e.g. a crashed/disconnected pooled {@link Browser}) and
     * a render rejected because this renderer is shutting down — surfaces as a
     * {@link ReportRenderException}; callers never see a raw Playwright or executor exception. The
     * checked-out pair is always returned to the pool, success or failure.
     */
    public byte[] renderRaw(String url, long settleMs) {
        PooledBrowser pb = checkout(url);
        long start = System.currentTimeMillis();
        boolean timedOut = false;
        try {
            BrowserContext ctx = createContext(pb.browser, url);
            try {
                CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> doRender(ctx, url, settleMs, timeoutMs), renderExecutor);
                byte[] pdf = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                log.info("Rendered [{}] in {} ms, {} bytes", safeHost(url), System.currentTimeMillis() - start, pdf.length);
                return pdf;
            } catch (RejectedExecutionException e) {
                log.error("Render rejected for [{}] (renderer is shutting down): {}", safeHost(url), e.getMessage());
                throw new ReportRenderException("Report render rejected, renderer is shutting down: " + safeHost(url), e);
            } catch (TimeoutException e) {
                timedOut = true;
                log.error("Render timed out after {} ms for [{}]", timeoutMs, safeHost(url));
                throw new ReportRenderException("Report render timed out after " + timeoutMs + " ms: " + safeHost(url), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Render failed for [{}]: {}", safeHost(url), cause.getMessage());
                throw new ReportRenderException("Report render failed for " + safeHost(url) + ": " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReportRenderException("Report render interrupted: " + safeHost(url), e);
            } finally {
                closeQuietly(ctx);
            }
        } finally {
            checkin(pb, timedOut);
        }
    }

    /**
     * Renders a real ThingsBoard dashboard by driving PE's {@code openReport} handshake (design spec
     * §6.3) against a checked-out pooled pair, then wraps the captured bytes as a {@link ReportData}
     * (name resolved via {@link ReportUtils#prepareReportName}, content type from {@code options}'
     * {@code type}). Shares {@link #pool}'s checkout/timeout/checkin envelope with {@link #renderRaw}
     * (same guarantees: blocks only when every pair is checked out, hard {@code timeoutMs} deadline,
     * timed-out pairs are closed and self-heal on a later {@link #checkout}, checked-out pair is
     * always returned) — kept as an independent copy rather than a shared private helper because the
     * two per-page routines ({@link #doRender} vs {@link #doRenderDashboard}) differ enough (byte[]
     * vs ReportData, and {@link #doRenderDashboard} throws its own precisely-worded
     * {@link ReportRenderException}s that must survive un-rewrapped, see the {@code cause instanceof}
     * check below) that a shared envelope would need its own branching anyway.
     * <p>
     * The handshake, per PE's {@code chunk-42C54VSB.js} (ported browser-side to
     * {@code report.service.ts}) and design spec §6.3:
     * <ol>
     * <li>{@link Page#exposeFunction} registers {@code postWebReportResult} <em>before</em>
     * navigating — the UI polls every 20 ms for it and calls it once logged in and ready.</li>
     * <li>{@link Page#navigate} to {@code {baseUrl}/dashboards/{dashboardId}?reportView=true&accessToken=<jwt>}
     * (+ {@code &state=} when set).</li>
     * <li>The first {@code postWebReportResult} call is that bare "ready" ping (PE always sends
     * {@code {success:true}} with no {@code pageHeight}/{@code error} key at all) — on it, send the
     * {@code openReport} command via {@code window.postMessage(JSON.stringify({type,data}), '*')}.</li>
     * <li>The <em>second</em> call is the real result: {@code {success, pageHeight, error?}}.</li>
     * <li>On success: size the viewport to {@code pageHeight} (+ {@code pageWidth} when supplied,
     * else the page's current viewport width), then capture. On failure/timeout: throw — PE's three
     * verbatim UI timeout strings ({@code "Wait for report page timed out!"} etc.) pass through
     * unmodified as the exception message.</li>
     * </ol>
     */
    public ReportData renderDashboard(String baseUrl, String accessToken, RenderOptions options) {
        String navigateUrl = buildNavigateUrl(baseUrl, options, accessToken);
        PooledBrowser pb = checkout(navigateUrl);
        long start = System.currentTimeMillis();
        boolean timedOut = false;
        try {
            BrowserContext ctx = createContext(pb.browser, navigateUrl);
            try {
                CompletableFuture<ReportData> future = CompletableFuture.supplyAsync(
                        () -> doRenderDashboard(ctx, navigateUrl, accessToken, options, timeoutMs), renderExecutor);
                ReportData result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                log.info("Rendered dashboard [{}] in {} ms, {} bytes", options.getDashboardId(),
                        System.currentTimeMillis() - start, result.getData().length);
                return result;
            } catch (RejectedExecutionException e) {
                log.error("Dashboard render rejected for [{}] (renderer is shutting down): {}", safeHost(navigateUrl), e.getMessage());
                throw new ReportRenderException("Report render rejected, renderer is shutting down: " + safeHost(navigateUrl), e);
            } catch (TimeoutException e) {
                timedOut = true;
                log.error("Dashboard render timed out after {} ms for [{}]", timeoutMs, safeHost(navigateUrl));
                throw new ReportRenderException("Report render timed out after " + timeoutMs + " ms: " + safeHost(navigateUrl), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Dashboard render failed for [{}]: {}", safeHost(navigateUrl), cause.getMessage());
                // doRenderDashboard already throws a precisely-worded ReportRenderException (PE's
                // verbatim UI error string, or a clear timeout/failure message) - re-throw it as-is
                // instead of burying that message under another "Report render failed for X: " prefix.
                if (cause instanceof ReportRenderException) {
                    throw (ReportRenderException) cause;
                }
                throw new ReportRenderException("Report render failed for " + safeHost(navigateUrl) + ": " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReportRenderException("Report render interrupted: " + safeHost(navigateUrl), e);
            } finally {
                closeQuietly(ctx);
            }
        } finally {
            checkin(pb, timedOut);
        }
    }

    /**
     * Checks out a pair from {@link #pool}, blocking if every pair is currently checked out (this is
     * what bounds concurrency — there is no separate semaphore). Self-heals before handing the pair to
     * the caller: if the drawn slot is a "dead" placeholder (left behind by a prior {@link #checkin}
     * after a timeout) or its {@link Browser} has otherwise disconnected — a driver-level crash
     * unrelated to a timeout, {@link PooledBrowser#isAlive()} catches that too — it is best-effort
     * closed and a fresh pair is launched in its place. If that relaunch itself fails, a dead placeholder
     * is offered back to {@link #pool} (preserving the pool's size instead of shrinking it) and a
     * {@link ReportRenderException} is thrown, scoping the launch failure to this one caller; the dead
     * slot is retried by whichever caller next draws it.
     */
    private PooledBrowser checkout(String url) {
        PooledBrowser pb;
        try {
            pb = pool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportRenderException("Interrupted while waiting for a render slot: " + safeHost(url), e);
        }
        if (pb.isAlive()) {
            return pb;
        }
        log.info("Self-healing a dead or disconnected render slot on checkout for [{}]", safeHost(url));
        closePooledBrowser(pb);
        try {
            return launchPooledBrowser();
        } catch (RuntimeException e) {
            offerToPool(new PooledBrowser(null, null));
            log.error("Failed to launch a replacement pooled browser while checking out a render slot for [{}]: {}", safeHost(url), e.getMessage());
            throw new ReportRenderException("Failed to launch a replacement browser for a render slot: " + safeHost(url), e);
        }
    }

    /**
     * Returns a checked-out pair to {@link #pool}, lazily: never blocks (uses
     * {@link BlockingQueue#offer}, not {@code put}) and never launches a browser here. A pair that just
     * timed out may be wedged (its driver connection is now in an unknown state), so it is best-effort
     * closed and replaced with a "dead" placeholder ({@code new PooledBrowser(null, null)}) instead of
     * being handed back as-is — relaunching is deferred to {@link #checkout(String)}, which self-heals
     * whichever caller next draws that dead slot. This keeps {@code checkin} free of any operation (a
     * browser launch) that could itself fail or block inside a {@code finally} block and mask the
     * render's already-decided outcome (its return value or its own exception); {@link #pool} never
     * shrinks, since a dead placeholder still occupies the slot.
     */
    private void checkin(PooledBrowser pb, boolean timedOut) {
        if (timedOut) {
            log.warn("Closing a pooled browser after a render timeout; the freed slot will self-heal on a later checkout");
            closePooledBrowser(pb);
            offerToPool(new PooledBrowser(null, null));
            return;
        }
        offerToPool(pb);
    }

    /**
     * {@link BlockingQueue#offer} is guaranteed to succeed here: the checkout/checkin accounting
     * invariant (exactly one {@link #checkout} precedes every {@code checkin}/dead-placeholder offer)
     * guarantees a free slot always exists in {@link #pool}, so this never actually blocks or fails under
     * that invariant. A {@code false} return would indicate a pool-accounting bug; logged, not thrown, so
     * a bug here can't mask the render's already-decided outcome.
     */
    private void offerToPool(PooledBrowser pb) {
        if (!pool.offer(pb)) {
            log.error("pool.offer() returned false returning a render slot; this indicates a pool accounting bug and the slot is now lost");
        }
    }

    private PooledBrowser launchPooledBrowser() {
        Playwright pw = Playwright.create();
        try {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (browserPath != null && !browserPath.isBlank()) {
                options.setExecutablePath(Paths.get(browserPath));
            }
            Browser browser = pw.chromium().launch(options);
            return new PooledBrowser(pw, browser);
        } catch (RuntimeException e) {
            pw.close();
            throw e;
        }
    }

    /**
     * Wraps {@link Browser#newContext()} on the caller's thread so a crashed/disconnected pooled
     * {@link Browser} (or any other driver-level failure) surfaces as {@link ReportRenderException} like
     * every other failure path, instead of a raw Playwright exception escaping uncaught. Kept synchronous
     * (not pushed into the async render lambda) so the context is always created before the timeout race
     * begins — a slow-but-hanging {@code newContext()} must still be force-closeable, not orphaned on the
     * render executor.
     */
    private static BrowserContext createContext(Browser browser, String url) {
        try {
            return browser.newContext();
        } catch (RuntimeException e) {
            log.error("Failed to create browser context for [{}]: {}", safeHost(url), e.getMessage());
            throw new ReportRenderException("Failed to create browser context for " + safeHost(url) + ": " + e.getMessage(), e);
        }
    }

    private static byte[] doRender(BrowserContext ctx, String url, long settleMs, long timeoutMs) {
        Page page = ctx.newPage();
        // Intentionally coupled to the same timeoutMs as the outer future.get(timeoutMs) in renderRaw:
        // without this, a timeoutMs larger than Playwright's own 30s default action-timeout would let
        // Playwright's internal timeout fire first, misclassifying a hang as a plain ExecutionException
        // instead of the outer JDK-level TimeoutException — so the pool wouldn't recycle the pair (see
        // `timedOut`) even though it may be left in the same wedged state a real timeout leaves it in.
        page.setDefaultTimeout(timeoutMs);
        page.navigate(url);
        if (settleMs > 0) {
            page.waitForTimeout(settleMs);
        }
        return page.pdf();
    }

    /**
     * The {@code openReport} handshake against one fresh, not-yet-navigated {@link Page}
     * (see {@link #renderDashboard} for the full sequence and the design-spec citation). Both waits
     * below are bounded by {@code timeoutMs} — the same deadline {@link #renderDashboard}'s outer
     * {@code future.get(timeoutMs)} enforces on the caller — so this method can't block its
     * render-executor thread past that budget even in the pathological case where
     * {@code postWebReportResult} is never called at all (proven by
     * {@code DashboardRenderIntegrationTest}'s never-ready fixture): the outer envelope has already
     * reported the timeout and force-closed the context by then, and this thread unblocks on its own
     * shortly after via its own bounded poll loop (see {@link #awaitSignal}) rather than hanging
     * forever.
     */
    private static ReportData doRenderDashboard(BrowserContext ctx, String navigateUrl, String accessToken,
                                                 RenderOptions options, long timeoutMs) {
        Page page = ctx.newPage();
        page.setDefaultTimeout(timeoutMs);

        CompletableFuture<Void> readySignal = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> resultSignal = new CompletableFuture<>();

        // Registered before navigation (handshake point 1) so it's present for the UI's very first
        // document, not just later ones. The UI calls this exactly twice per openReport: once bare
        // ({success:true}, no pageHeight/error key at all) the moment it's logged in and listening —
        // that's the "ready" ping, our cue to send the openReport command — and once with the real
        // {success,pageHeight,error?} result once the dashboard has settled.
        page.exposeFunction("postWebReportResult", args -> {
            Map<String, Object> payload = extractPayload(args);
            if (!readySignal.isDone()) {
                readySignal.complete(null);
            } else {
                resultSignal.complete(payload);
            }
            return null;
        });

        page.navigate(navigateUrl);

        awaitSignal(page, readySignal, timeoutMs, "Timed out waiting for the report-view UI to become ready");

        page.evaluate("(json) => window.postMessage(json, '*')", buildOpenReportMessage(accessToken, options, timeoutMs));

        Map<String, Object> result = awaitSignal(page, resultSignal, timeoutMs, "Timed out waiting for the dashboard report result");

        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new ReportRenderException("Dashboard report render failed: " + extractErrorMessage(result));
        }

        int pageHeight = extractPageHeight(result);
        int pageWidth = options.getPageWidth() != null ? options.getPageWidth() : page.viewportSize().width;
        page.setViewportSize(pageWidth, pageHeight);

        return capture(page, options, pageWidth, pageHeight);
    }

    /**
     * {@code type} drives PDF vs PNG vs JPEG (PE's {@code DashboardReportConfig.type} swagger doc:
     * "can be PDF | PNG | JPEG", case-insensitive here since PE's own contract doesn't pin a case);
     * unset/unrecognized falls back to PDF, the primary/default format. PDF paper size is pinned to
     * the just-set viewport pixel size (not a fixed paper format) so the single-page PDF matches the
     * captured content instead of being clipped/padded to "Letter".
     */
    private static ReportData capture(Page page, RenderOptions options, int pageWidth, int pageHeight) {
        String type = options.getType() != null ? options.getType().trim().toLowerCase() : "pdf";
        byte[] data;
        String contentType;
        switch (type) {
            case "png":
                data = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG));
                contentType = "image/png";
                break;
            case "jpeg":
            case "jpg":
                data = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG));
                contentType = "image/jpeg";
                break;
            case "pdf":
            default:
                data = page.pdf(new Page.PdfOptions()
                        .setWidth(pageWidth + "px")
                        .setHeight(pageHeight + "px")
                        .setPrintBackground(true));
                contentType = "application/pdf";
                break;
        }
        String name = ReportUtils.prepareReportName(options.getNamePattern(), new Date(), options.getTimezone());
        return ReportData.builder().data(data).name(name).contentType(contentType).build();
    }

    /**
     * Waits up to {@code timeoutMs} for {@code signal} (one of {@link #doRenderDashboard}'s two
     * {@code postWebReportResult} futures — ready ping, final result), translating a timeout into a
     * {@link ReportRenderException} carrying {@code timeoutMessage}.
     * <p>
     * <b>Polls with a cheap {@link Page#evaluate} "nudge" rather than a bare
     * {@link CompletableFuture#get(long, TimeUnit)}</b> — empirically required (proven with a
     * minimal, exposeFunction-only reproduction outside this codebase, isolating Playwright-Java
     * 1.49's sync client from everything else in this class): an {@link Page#exposeFunction} binding
     * invoked as an indirect result of this renderer's own {@code page.evaluate(...postMessage...)}
     * (i.e. browser JS calls the exposed function from inside a {@code window} {@code message}
     * listener) does not reliably reach this callback while the calling thread is doing nothing but
     * a plain JDK {@code CompletableFuture.get()} — it only gets flushed through once
     * <em>something else</em> drives another Playwright/CDP round trip on the same {@link Page}. A
     * direct (non-postMessage-mediated) exposed-function call, by contrast, arrives immediately with
     * no nudging needed — this is specific to the postMessage-relayed path the {@code openReport}
     * handshake's second (result) call always takes, and, empirically, sometimes even the first
     * (ready) call when nothing has touched the page yet. Each poll tick's {@code evaluate} failure
     * (e.g. the page was just force-closed by the outer envelope's own timeout) is swallowed — the
     * deadline check below is what actually governs the wait, not the nudge call's own outcome.
     */
    private static <T> T awaitSignal(Page page, CompletableFuture<T> signal, long timeoutMs, String timeoutMessage) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!signal.isDone()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new ReportRenderException(timeoutMessage);
            }
            try {
                page.evaluate("() => true");
            } catch (RuntimeException e) {
                log.debug("Nudge evaluate failed while awaiting a report-view signal (will keep polling until the deadline): {}", e.getMessage());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ReportRenderException(timeoutMessage + " (interrupted)", e);
            }
        }
        try {
            return signal.get();
        } catch (ExecutionException e) {
            throw new ReportRenderException(timeoutMessage + ": " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportRenderException(timeoutMessage + " (interrupted)", e);
        }
    }

    /**
     * {@code postWebReportResult} is called with exactly one argument, the UI's result object; a
     * JSON object argument arrives here as a {@code Map<String,Object>} (Playwright's binding
     * deserializer). Defensive fallback to an empty map covers a same-origin fallback
     * {@code postMessage({type:'reportResult',...})} call shape or any other unexpected arg vs
     * throwing out of the exposed-function callback itself.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractPayload(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Map) {
            return (Map<String, Object>) args[0];
        }
        return Map.of();
    }

    /**
     * {@code error} is whatever the UI sent under that key — normally a {@code String}, but the
     * defensive fallback {@code postMessage({type:'reportResult',...})} path (see
     * {@link #extractPayload}) or any other unexpected result shape could hand this a nested
     * {@code Map}/{@code List}/other type. {@link String#valueOf} renders any of those (never
     * {@code null}, since that's guarded separately) without risking a surprise exception out of
     * this failure-reporting path itself — the one place callers rely on to explain what went
     * wrong, so it must not be what throws instead.
     */
    private static String extractErrorMessage(Map<String, Object> result) {
        Object error = result.get("error");
        return error != null ? String.valueOf(error) : "unknown error";
    }

    /**
     * {@code pageHeight} drives the post-success viewport resize right after this call returns; a
     * missing key or an unexpected non-numeric value would otherwise surface as a raw
     * {@link NullPointerException}/{@link ClassCastException} out of an unchecked {@code (Number)}
     * cast instead of the {@link ReportRenderException} contract every other failure path in this
     * class already honors.
     */
    private static int extractPageHeight(Map<String, Object> result) {
        Object pageHeight = result.get("pageHeight");
        if (!(pageHeight instanceof Number)) {
            throw new ReportRenderException("Dashboard report render failed: missing or non-numeric pageHeight in result: " + pageHeight);
        }
        return ((Number) pageHeight).intValue();
    }

    /**
     * Builds the {@code {type:'openReport', data:{...}}} envelope (handshake point 3) as an
     * already-serialized JSON string — passed through {@link Page#evaluate(String, Object)} as a
     * plain string argument (trivially Gson-serializable by Playwright's own arg marshalling) rather
     * than handing Playwright a Jackson {@code JsonNode} tree (e.g. {@code options.getTimewindow()})
     * directly, which Playwright's serializer doesn't know how to walk. The UI's
     * {@code onWindowMessage} accepts a string {@code event.data} and {@code JSON.parse}s it itself,
     * so this is wire-compatible with report.service.ts either way.
     * <p>
     * {@code data.timeout} carries {@link #openReportTimeoutMs}, not {@code timeoutMs} verbatim —
     * see that method's Javadoc for why. Package-private (not {@code private}) purely so
     * {@code PlaywrightWebReportRendererTest} can assert the emitted JSON directly rather than only
     * through a full browser round-trip.
     */
    static String buildOpenReportMessage(String accessToken, RenderOptions options, long timeoutMs) {
        ObjectNode data = JacksonUtil.newObjectNode();
        data.put("accessToken", accessToken);
        data.put("dashboardId", options.getDashboardId());
        data.put("timeout", openReportTimeoutMs(timeoutMs));
        if (options.getState() != null) {
            data.put("state", options.getState());
        }
        if (options.getTimewindow() != null) {
            data.set("reportTimewindow", options.getTimewindow());
        }
        ObjectNode envelope = JacksonUtil.newObjectNode();
        envelope.put("type", "openReport");
        envelope.set("data", data);
        return JacksonUtil.toString(envelope);
    }

    /**
     * report.service.ts:291 ({@code waitForLayoutReady(command.timeout)}) feeds this same number,
     * unsplit, to <em>both</em> of its sequential UI-readiness stages ({@code waitForReportPage}
     * then {@code waitForWebsocketData}) — and each stage falls back to a hardcoded 3000 ms
     * default whenever {@code command.timeout} is absent, silently ignoring this renderer's own
     * configured {@code timeoutMs} entirely. Before this method existed, {@link #buildOpenReportMessage}
     * never set {@code data.timeout} at all, so every dashboard render was implicitly capped at
     * 3000 ms per stage regardless of the production default (120000 ms, spec §13) — a real
     * dashboard taking longer than that to settle would fail with a spurious "Wait for report page
     * timed out!" well inside this renderer's actual budget.
     * <p>
     * Trims a fixed 5000 ms capture margin off {@code timeoutMs} — rather than passing it through
     * verbatim — so this render's own remaining work after the UI replies (viewport resize,
     * {@code page.pdf()}/{@code page.screenshot()}) has room to finish before the outer
     * {@code future.get(timeoutMs)} deadline ({@link #renderDashboard}) fires; the 3000 ms floor
     * keeps a very small configured {@code timeoutMs} from starving both UI stages down to nothing
     * (and matches the UI's own pre-existing per-stage default, so a tiny configured timeout
     * degrades to today's behavior rather than something worse).
     */
    private static long openReportTimeoutMs(long timeoutMs) {
        return Math.max(3000L, timeoutMs - 5000L);
    }

    /**
     * {@code {baseUrl}/dashboards/{dashboardId}?reportView=true&accessToken=<jwt>[&state=<state>]}
     * (design spec §6.3 point 2). {@code accessToken}/{@code state} are URL-encoded defensively: a
     * JWT's base64url alphabet already round-trips through {@link URLEncoder} unchanged, but a
     * dashboard {@code state} can be arbitrary base64 (padding {@code =}, {@code +}, {@code /}) that
     * would otherwise corrupt the query string (a raw {@code +} decodes back as a space).
     */
    private static String buildNavigateUrl(String baseUrl, RenderOptions options, String accessToken) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/dashboards/").append(options.getDashboardId())
                .append("?reportView=true&accessToken=").append(urlEncode(accessToken));
        if (options.getState() != null && !options.getState().isEmpty()) {
            url.append("&state=").append(urlEncode(options.getState()));
        }
        return url.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(BrowserContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.close();
        } catch (Exception e) {
            log.warn("Failed to close render browser context: {}", e.getMessage());
        }
    }

    /**
     * Best-effort close; null-safe so a "dead" placeholder ({@link PooledBrowser#browser}/
     * {@link PooledBrowser#playwright} both {@code null}) is a no-op, not a {@code NullPointerException}.
     * Never throws — every close is independently swallowed so a failure closing one half never skips
     * the other, and callers (several of them inside a {@code finally} block) can rely on this method
     * itself never being the thing that fails.
     */
    private static void closePooledBrowser(PooledBrowser pb) {
        if (pb.browser != null) {
            try {
                pb.browser.close();
            } catch (Exception e) {
                log.warn("Failed to close pooled browser: {}", e.getMessage());
            }
        }
        if (pb.playwright != null) {
            try {
                pb.playwright.close();
            } catch (Exception e) {
                log.warn("Failed to close pooled Playwright driver: {}", e.getMessage());
            }
        }
    }

    private static String safeHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url.length() > 40 ? url.substring(0, 40) : url;
        } catch (Exception e) {
            return "unparseable-url";
        }
    }

    /**
     * One thread-confined {@link Playwright} driver connection plus the {@link Browser} launched through
     * it — or a "dead" placeholder ({@code playwright == null && browser == null}, see {@link #isAlive()})
     * standing in for a slot whose real pair was already closed (after a timeout), kept in {@link #pool}
     * purely to preserve the pool's size until {@link #checkout(String)} lazily relaunches it. A live
     * pair is never driven by more than one thread at a time: it only leaves {@link #pool} via
     * {@link #checkout(String)} (one caller at a time, by construction of {@link BlockingQueue#take()})
     * and only re-enters via {@link #checkin(PooledBrowser, boolean)} once that caller's render has fully
     * finished with it.
     */
    private static final class PooledBrowser {
        private final Playwright playwright;
        private final Browser browser;

        private PooledBrowser(Playwright playwright, Browser browser) {
            this.playwright = playwright;
            this.browser = browser;
        }

        /**
         * {@code false} for a dead placeholder ({@link #browser} {@code null}) and for a pair whose
         * {@link Browser} has disconnected since it was launched or last used — a driver-level crash
         * caught here regardless of whether it followed a timeout, covering driver-corrupted pairs in
         * general, not just the timeout path.
         */
        private boolean isAlive() {
            return browser != null && browser.isConnected();
        }
    }

}
