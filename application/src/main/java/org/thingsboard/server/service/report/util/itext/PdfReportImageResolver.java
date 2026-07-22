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
