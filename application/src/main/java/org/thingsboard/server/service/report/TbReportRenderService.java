// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report;

import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.service.report.context.TbReportCtx;

/**
 * One render engine per {@link TbReportFormat} (design spec §6.1): given a fully-populated
 * {@link ReportTask} and its per-render {@link TbReportCtx}, produces the rendered {@link ReportData}
 * for that task's report template.
 * <p>
 * PE names this interface {@code ReportService}. Renamed {@code TbReportRenderService} here because
 * the dao layer already owns {@code org.thingsboard.server.dao.report.ReportService} (Task 8) and a
 * single Java package can't declare two top-level types with the same simple name. {@link
 * TbReportService} keeps the PE dispatcher name — it builds the ctx (via {@link
 * org.thingsboard.server.service.report.context.TbReportCtxProvider}), picks a {@code
 * TbReportRenderService} by format, and persists the result.
 * <p>
 * R2a added the {@link TbReportCtx} parameter (spec §5.5) — the engine needs the reconstructed
 * SecurityUser + image resolver + typed configuration the ctx carries.
 */
public interface TbReportRenderService {

    ReportData generateReport(ReportTask task, TbReportCtx ctx);

    TbReportFormat getFormat();

}
