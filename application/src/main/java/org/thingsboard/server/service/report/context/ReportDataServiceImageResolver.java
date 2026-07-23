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

import org.thingsboard.server.service.report.datasource.ReportDataService;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;

/**
 * {@link PdfReportImageResolver} backed by the R2b {@link ReportDataService}. The flying-saucer user agent
 * ({@code PdfReportUserAgent}) calls this whenever a report fragment references a TB image URI
 * (<code>/api/images/tenant|system|public/&lt;key&gt;</code>) so the bytes are inlined into the PDF at
 * render time — including {@code sourceType=entityKey} images, whose URL is resolved from an entity's
 * telemetry/attribute value and is itself such a URI.
 * <p>
 * This is the "adapter that delegates to the real report data service" R2a's {@code PdfReportImageResolver}
 * anticipated: it routes every image download through {@link ReportDataService#downloadImage} /
 * {@link ReportDataService#downloadPublicImage}, i.e. through the same {@code getSecurityUser(ctx)}
 * permission chokepoint as every other report query. Scope (tenant/system/public) comes from the trusted
 * {@link TbReportCtx}, never the URI. (R2a's stop-gap {@code ImageServiceReportImageResolver}, which wired
 * {@code ImageService} directly, is superseded by this.)
 * <p>
 * <b>Back-reference.</b> The ctx holds this resolver ({@link TbReportCtx#getImageResolver()}) and this
 * resolver needs the ctx to scope its downloads — a construction cycle. {@link LocalTbReportCtxProvider}
 * breaks it by constructing the resolver first, building the ctx with it, then calling {@link #setCtx}
 * once. {@link #getImageResolver} is only ever consumed during rendering, long after {@code newContext}
 * has completed the wiring.
 */
public class ReportDataServiceImageResolver implements PdfReportImageResolver {

    private final ReportDataService reportDataService;
    // Written once by setCtx immediately after construction, then read during the (synchronous) render.
    // volatile guards visibility in case an image is ever resolved off the render thread.
    private volatile TbReportCtx ctx;

    public ReportDataServiceImageResolver(ReportDataService reportDataService) {
        this.reportDataService = reportDataService;
    }

    /** Completes the ctx back-reference (see class javadoc). Called once by the provider right after the ctx is built. */
    void setCtx(TbReportCtx ctx) {
        this.ctx = ctx;
    }

    @Override
    public byte[] downloadImage(String type, String key) throws Exception {
        return reportDataService.downloadImage(type, key, ctx);
    }

    @Override
    public byte[] downloadPublicImage(String key) throws Exception {
        return reportDataService.downloadPublicImage(key, ctx);
    }
}
