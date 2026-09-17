// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.context;

import org.thingsboard.server.common.data.job.task.ReportTask;

/**
 * Builds the per-render {@link TbReportCtx} from a {@link ReportTask} (PE {@code TbReportCtxProvider},
 * {@code report.context}). PE ships two impls — a {@code LocalTbReportCtxProvider} (in-process) and a
 * {@code RemoteTbReportCtxProvider} (the standalone {@code tb-report} microservice). Inferrix runs the
 * renderer in-process only, so R2a ships {@link LocalTbReportCtxProvider} alone.
 */
public interface TbReportCtxProvider {

    TbReportCtx newContext(ReportTask task);

}
