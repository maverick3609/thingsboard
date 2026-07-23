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
import org.thingsboard.script.api.tbel.TbelInvokeService;
import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.service.report.datasource.ReportDataService;
import org.thingsboard.server.service.report.render.ReportUtils;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;

/**
 * In-process {@link TbReportCtxProvider} (PE {@code LocalTbReportCtxProvider}). Reconstructs the report
 * user's {@link SecurityUser} from the task's minted access token via the existing public {@link
 * JwtTokenFactory#parseAccessJwtToken} (spec §6) — a <b>second consumer</b> of that method ({@code
 * JwtAuthenticationProvider} is the first), so {@link JwtTokenFactory} is <b>not</b> edited — then builds
 * the full per-render {@link TbReportCtx}: the reconstructed {@link SecurityUser} (the render's trust
 * anchor), the typed configuration, the render timezone/created-time, and the R2b data layer's collaborators
 * — the {@link TbelInvokeService} (post-processing scripts) and a {@link ReportDataServiceImageResolver}
 * that fetches report images through {@link ReportDataService} (i.e. through its permission chokepoint).
 * <p>
 * The typed {@link ReportTemplateConfig} rides on the {@link ReportTask} itself ({@code
 * reportTemplateConfig}, populated by both producers — the scheduled {@code ReportJobProcessor} and the
 * on-demand {@code ReportController#buildReportTask}).
 * <p>
 * Gated by the same {@code reports.renderer.enabled} property as the rest of the engine (its consumer
 * {@code TbReportService} and the injected {@link ReportDataService} are likewise gated), so the platform
 * boots renderer-off.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reports.renderer", name = "enabled", havingValue = "true")
public class LocalTbReportCtxProvider implements TbReportCtxProvider {

    private final JwtTokenFactory tokenFactory;
    private final ReportDataService reportDataService;
    private final TbelInvokeService tbelInvokeService;

    @Override
    public TbReportCtx newContext(ReportTask task) {
        SecurityUser securityUser = tokenFactory.parseAccessJwtToken(task.getAccessToken());
        ReportTemplateConfig configuration = task.getReportTemplateConfig();
        // The image resolver needs the ctx (to scope its downloads) and the ctx holds the resolver — build the
        // resolver first, hand it to the ctx, then complete the back-reference. See ReportDataServiceImageResolver.
        ReportDataServiceImageResolver imageResolver = new ReportDataServiceImageResolver(reportDataService);
        TbReportCtx ctx = TbReportCtx.builder()
                .tenantId(task.getTenantId())
                .configuration(configuration)
                .timeZone(task.getTimezone())
                .userId(task.getUserId())
                .userOwnerId(task.getUserOwnerId())
                .accessToken(task.getAccessToken())
                .accessTokenExpTs(task.getAccessTokenExpirationTs())
                .reportCreatedTime(ReportUtils.formatTimestamp(System.currentTimeMillis(),
                        configuration != null ? configuration.getTimeDataPattern() : null, task.getTimezone()))
                .imageResolver(imageResolver)
                .securityUser(securityUser)
                .tbelInvokeService(tbelInvokeService)
                .build();
        imageResolver.setCtx(ctx);
        return ctx;
    }
}
