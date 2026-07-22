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

import lombok.RequiredArgsConstructor;
import org.thingsboard.server.common.data.TbResourceInfo;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.resource.ImageService;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;

/**
 * {@link PdfReportImageResolver} backed by the DAO {@link ImageService}, scoped to one tenant. The
 * flying-saucer user agent ({@code PdfReportUserAgent}) calls this when a report fragment references a
 * TB image URI (<code>/api/images/tenant|system|public/&lt;key&gt;</code>) so the bytes are inlined into
 * the PDF at render time.
 * <p>
 * In PE the user agent held a {@code ReportDataService} whose {@code downloadImage} delegated to the
 * same {@link ImageService}; R2a wires {@link ImageService} directly (the full data service is R2b).
 * Every lookup is tenant-scoped: {@code tenant} keys resolve against this resolver's tenant, {@code
 * system} keys against {@link TenantId#SYS_TENANT_ID}, {@code public} keys by their public resource key.
 */
@RequiredArgsConstructor
public class ImageServiceReportImageResolver implements PdfReportImageResolver {

    private final ImageService imageService;
    private final TenantId tenantId;

    @Override
    public byte[] downloadImage(String type, String key) {
        TenantId scope = "system".equals(type) ? TenantId.SYS_TENANT_ID : tenantId;
        TbResourceInfo info = imageService.getImageInfoByTenantIdAndKey(scope, key);
        if (info == null) {
            return null;
        }
        return imageService.getImageData(info.getTenantId(), info.getId());
    }

    @Override
    public byte[] downloadPublicImage(String key) {
        TbResourceInfo info = imageService.getPublicImageInfoByKey(key);
        if (info == null) {
            return null;
        }
        return imageService.getImageData(info.getTenantId(), info.getId());
    }
}
