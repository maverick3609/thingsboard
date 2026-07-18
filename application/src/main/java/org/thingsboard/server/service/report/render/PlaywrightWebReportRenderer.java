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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-process report renderer built on Playwright-Java, replacing PE's external
 * {@code tb-web-report} microservice: one shared headless-Chromium {@link Browser} for the whole
 * JVM, with each render getting its own {@link BrowserContext} for crash/state isolation.
 * <p>
 * Concurrency is bounded by a {@link Semaphore} ({@code maxConcurrent}); each render is wrapped in
 * a {@link CompletableFuture} bounded by a hard timeout ({@code timeoutMs}) — on timeout the
 * in-flight context is force-closed (unblocking whatever Playwright call the render thread is
 * stuck in) and a {@link ReportRenderException} is thrown. The semaphore permit is always released,
 * success or failure.
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
    private final Semaphore semaphore;

    private Playwright playwright;
    private Browser browser;
    private ExecutorService renderExecutor;

    public PlaywrightWebReportRenderer(int maxConcurrent, long timeoutMs, String browserPath) {
        this.maxConcurrent = maxConcurrent;
        this.timeoutMs = timeoutMs;
        this.browserPath = browserPath;
        this.semaphore = new Semaphore(maxConcurrent);
    }

    @PostConstruct
    public void init() {
        log.info("Starting Playwright web report renderer, maxConcurrent [{}], timeoutMs [{}]", maxConcurrent, timeoutMs);
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        if (browserPath != null && !browserPath.isBlank()) {
            options.setExecutablePath(Paths.get(browserPath));
        }
        browser = playwright.chromium().launch(options);
        renderExecutor = Executors.newCachedThreadPool();
    }

    @PreDestroy
    public void destroy() {
        log.info("Stopping Playwright web report renderer");
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /**
     * Renders {@code url} to a PDF: navigates a fresh, isolated {@link BrowserContext}/{@link Page}
     * to it, optionally settles for {@code settleMs}, then snapshots {@link Page#pdf()}. Bounded by
     * this renderer's {@code timeoutMs}; on timeout the context is force-closed and a
     * {@link ReportRenderException} is thrown instead of hanging the caller.
     */
    public byte[] renderRaw(String url, long settleMs) {
        acquirePermit(url);
        long start = System.currentTimeMillis();
        BrowserContext ctxHolder = null;
        try {
            final BrowserContext ctx = browser.newContext();
            ctxHolder = ctx;
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> doRender(ctx, url, settleMs, timeoutMs), renderExecutor);
            byte[] pdf = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("Rendered [{}] in {} ms, {} bytes", safeHost(url), System.currentTimeMillis() - start, pdf.length);
            return pdf;
        } catch (TimeoutException e) {
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
            closeQuietly(ctxHolder);
            semaphore.release();
        }
    }

    private void acquirePermit(String url) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportRenderException("Interrupted while waiting for a render slot: " + safeHost(url), e);
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

    private static String safeHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url.length() > 40 ? url.substring(0, 40) : url;
        } catch (Exception e) {
            return "unparseable-url";
        }
    }

}
