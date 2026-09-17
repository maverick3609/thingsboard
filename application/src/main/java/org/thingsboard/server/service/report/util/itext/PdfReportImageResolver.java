// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.util.itext;

/**
 * Image-fetch seam for {@link PdfReportUserAgent}. In PE the user agent held a
 * {@code ReportDataService} + {@code TbReportCtx} and called
 * {@code downloadImage(type, key, ctx)} / {@code downloadPublicImage(key, ctx)}. Those
 * collaborators arrive in a later R2b task, so C4 depends only on this minimal functional
 * seam; the later task supplies an adapter that delegates to the real report data service.
 */
public interface PdfReportImageResolver {

    /**
     * Download an internal (tenant/system) TB image by type and key.
     *
     * @param type {@code "tenant"} or {@code "system"}
     * @param key  the image resource key
     * @return the raw image bytes, or {@code null} if not resolvable
     */
    byte[] downloadImage(String type, String key) throws Exception;

    /**
     * Download a public TB image by its public key.
     *
     * @param key the public image key
     * @return the raw image bytes, or {@code null} if not resolvable
     */
    byte[] downloadPublicImage(String key) throws Exception;
}
