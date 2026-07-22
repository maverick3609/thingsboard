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

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;
import org.thingsboard.server.service.security.model.SecurityUser;

/**
 * Per-render context handed to every {@link org.thingsboard.server.service.report.TbReportRenderService}
 * (PE names this {@code TbReportCtx}, {@code report.context}). Carries everything the R2a engine needs
 * to turn one {@link ReportTask}'s typed {@link ReportTemplateConfig} into a PDF: the tenant scope, the
 * typed configuration, the render timezone, the report user, that user's minted access token, the
 * reconstructed {@link SecurityUser}, and the {@link PdfReportImageResolver} the flying-saucer user
 * agent uses to fetch tenant/system/public TB images.
 * <p>
 * <b>R2a-minimal (spec §6).</b> PE's ctx is {@code abstract}/{@code Closeable} and additionally carries
 * a {@code ReportDataService} + TBEL script cache + {@code createSubReportCxt(...)} — the whole
 * server-side data-access layer (entity/alarm/timeseries queries, sub-reports, post-processing scripts).
 * That layer is <b>R2b</b>: this class deliberately has no data-query methods and no {@code Closeable}
 * plumbing (nothing to release without the script cache). When R2b lands the {@code ReportDataService},
 * the data collaborators + {@code createSubReportCxt} seam attach here.
 * <p>
 * // R2b: server-side data queries (ReportDataService: entity/alarm/timeseries + sub-report ctx) attach here.
 */
@Getter
@Builder
@ToString
public class TbReportCtx {

    private final TenantId tenantId;
    private final ReportTemplateConfig configuration;
    private final String timeZone;
    private final UserId userId;
    private final String accessToken;
    private final PdfReportImageResolver imageResolver;
    private final SecurityUser securityUser;

}
