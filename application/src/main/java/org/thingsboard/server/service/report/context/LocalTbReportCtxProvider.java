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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.dao.resource.ImageService;
import org.thingsboard.server.service.report.util.itext.PdfReportImageResolver;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;

/**
 * In-process {@link TbReportCtxProvider} (PE {@code LocalTbReportCtxProvider}). Reconstructs the report
 * user's {@link SecurityUser} from the task's minted access token via the existing public {@link
 * JwtTokenFactory#parseAccessJwtToken} (spec §6) — R2a is the <b>second consumer</b> of that method
 * ({@code JwtAuthenticationProvider} is the first), so {@link JwtTokenFactory} is <b>not</b> edited —
 * and builds a tenant-scoped {@link ImageServiceReportImageResolver} for the flying-saucer user agent.
 * <p>
 * The typed {@link org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig} rides
 * on the {@link ReportTask} itself ({@code reportTemplateConfig}, populated by both producers — the
 * scheduled {@code ReportJobProcessor} and the on-demand {@code ReportController#buildReportTask}).
 * <p>
 * Gated by the same {@code reports.renderer.enabled} property as the rest of the engine: its only
 * consumer ({@code TbReportService}) is likewise gated, so the two appear/absent together.
 * <p>
 * // R2b: server-side data queries — the ReportDataService-backed ctx (entity/alarm/timeseries) attaches here.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class LocalTbReportCtxProvider implements TbReportCtxProvider {

    private final JwtTokenFactory tokenFactory;
    private final ImageService imageService;

    @Override
    public TbReportCtx newContext(ReportTask task) {
        SecurityUser securityUser = tokenFactory.parseAccessJwtToken(task.getAccessToken());
        PdfReportImageResolver imageResolver = new ImageServiceReportImageResolver(imageService, task.getTenantId());
        return TbReportCtx.builder()
                .tenantId(task.getTenantId())
                .configuration(task.getReportTemplateConfig())
                .timeZone(task.getTimezone())
                .userId(task.getUserId())
                .accessToken(task.getAccessToken())
                .imageResolver(imageResolver)
                .securityUser(securityUser)
                .build();
    }
}
