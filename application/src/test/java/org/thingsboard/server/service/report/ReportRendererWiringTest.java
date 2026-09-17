// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.service.report.context.LocalTbReportCtxProvider;
import org.thingsboard.server.service.report.render.PlaywrightWebReportRenderer;
import org.thingsboard.server.service.report.task.ReportTaskProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for a Spring bean cycle that forms ONLY when {@code reports.renderer.enabled=true}
 * — every other reporting test runs renderer-off, so this class is the one that boots the full
 * context with the renderer-gated beans active. R2a extends it to prove the whole engine graph wires:
 * the {@link ReportTaskProcessor} → {@link TbReportService} → {@link PdfReportService} (built from the
 * injected component-renderer list) → {@code DashboardReportService} chain plus the {@link
 * LocalTbReportCtxProvider}.
 * <p>
 * {@code DefaultTbServiceInfoProvider} eagerly collects {@code List<TaskProcessor>}, which includes
 * {@link ReportTaskProcessor}, whose {@code TbReportService -> DefaultDashboardReportService ->
 * SystemSecurityService -> MailService -> TbApiUsageReportClient -> HashPartitionService} chain loops
 * back to {@code DefaultTbServiceInfoProvider} — Spring reports "APPLICATION FAILED TO START / Unable
 * to start web server". {@link ReportTaskProcessor} injecting {@code TbReportService} {@code @Lazy}
 * severs the only reporting edge on that cycle. If that regresses, this application context fails to
 * start and this test errors before reaching the assertion.
 * <p>
 * {@link PlaywrightWebReportRenderer} is mocked so no headless Chromium launches during the test —
 * the point is the wiring graph, not rendering.
 */
@DaoSqlTest
@TestPropertySource(properties = {"reports.renderer.enabled=true"})
public class ReportRendererWiringTest extends AbstractControllerTest {

    @MockitoBean
    private PlaywrightWebReportRenderer playwrightWebReportRenderer;

    @Autowired(required = false)
    private ReportTaskProcessor reportTaskProcessor;

    @Autowired(required = false)
    private TbReportService tbReportService;

    @Autowired(required = false)
    private PdfReportService pdfReportService;

    @Autowired(required = false)
    private LocalTbReportCtxProvider localTbReportCtxProvider;

    @Test
    public void contextStartsWithRendererEnabledAndReportBeansWired() {
        assertThat(reportTaskProcessor)
                .as("ReportTaskProcessor must wire when reports.renderer.enabled=true (proves no bean cycle)")
                .isNotNull();
        assertThat(tbReportService).as("TbReportService dispatcher wired").isNotNull();
        assertThat(pdfReportService).as("PdfReportService engine wired (component-renderer registry built)").isNotNull();
        assertThat(localTbReportCtxProvider).as("LocalTbReportCtxProvider wired").isNotNull();
    }
}
