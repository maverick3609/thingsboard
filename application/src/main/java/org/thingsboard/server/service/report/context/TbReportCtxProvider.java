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
