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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * {@link BrowserContext}/{@link Page} on it, and {@link BlockingQueue#put(Object) returns} the pair when
 * done. A given pair is therefore only ever driven by one thread at a time; different pairs, with
 * different connections, can be driven by different threads truly concurrently with no shared mutable
 * driver state between them.
 * <p>
 * Each render is wrapped in a {@link CompletableFuture} bounded by a hard timeout ({@code timeoutMs}) —
 * on timeout the in-flight context is force-closed (unblocking whatever Playwright call the render
 * thread is stuck in) and a {@link ReportRenderException} is thrown. Because a timed-out render leaves
 * its pair's driver connection in an unknown state, that pair is closed and replaced with a freshly
 * launched one before being returned to the pool, so a wedged browser can't poison a later render that
 * happens to check out the same slot. The checked-out pair is always returned to the pool, success or
 * failure.
 * <p>
 * Disabled by default ({@code reports.renderer.enabled}); wiring the real bean (config-sourced
 * constructor args, dashboard handshake) lands in Task 13/26. This class is exercised directly
 * (plain {@code new} + {@link #init()}/{@link #destroy()}) to prove the render primitive.
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

    public PlaywrightWebReportRenderer(int maxConcurrent, long timeoutMs, String browserPath) {
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

    @PreDestroy
    public void destroy() {
        log.info("Stopping Playwright web report renderer");
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
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
     * on timeout the context is force-closed, the pair is recycled (closed and relaunched, since its
     * driver connection is now in an unknown state) rather than handed back as-is, and a
     * {@link ReportRenderException} is thrown instead of hanging the caller. Every failure path —
     * including context creation itself, e.g. a crashed/disconnected pooled {@link Browser} — surfaces
     * as a {@link ReportRenderException}; callers never see a raw Playwright exception. The checked-out
     * pair is always returned to the pool, success or failure.
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

    private PooledBrowser checkout(String url) {
        try {
            return pool.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportRenderException("Interrupted while waiting for a render slot: " + safeHost(url), e);
        }
    }

    /**
     * Returns a checked-out pair to {@link #pool}. A pair that just timed out is recycled first —
     * closed and replaced with a freshly-launched pair — since a timeout leaves its driver connection in
     * an unknown state and handing it back as-is risks poisoning a later, unrelated render (see class
     * Javadoc). If the relaunch itself fails, that pair is dropped rather than letting a pool-maintenance
     * failure escape this {@code finally} block and replace/mask the render's already-decided outcome
     * (its return value or its own exception); the pool simply runs one browser short from then on.
     */
    private void checkin(PooledBrowser pb, boolean timedOut) {
        PooledBrowser toReturn = pb;
        if (timedOut) {
            log.warn("Recycling pooled browser after a render timeout to clear any wedged driver state");
            try {
                closePooledBrowser(pb);
                toReturn = launchPooledBrowser();
            } catch (RuntimeException e) {
                log.error("Failed to relaunch a pooled browser after a timeout; pool will run one browser short: {}", e.getMessage());
                return;
            }
        }
        try {
            pool.put(toReturn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while returning a pooled browser; closing it instead of returning it to the pool");
            closePooledBrowser(toReturn);
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
        page.setDefaultTimeout(timeoutMs);
        page.navigate(url);
        if (settleMs > 0) {
            page.waitForTimeout(settleMs);
        }
        return page.pdf();
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

    private static void closePooledBrowser(PooledBrowser pb) {
        try {
            pb.browser.close();
        } catch (Exception e) {
            log.warn("Failed to close pooled browser: {}", e.getMessage());
        }
        try {
            pb.playwright.close();
        } catch (Exception e) {
            log.warn("Failed to close pooled Playwright driver: {}", e.getMessage());
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
     * it. Never driven by more than one thread at a time: a pair only leaves {@link #pool} via
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
    }

}
