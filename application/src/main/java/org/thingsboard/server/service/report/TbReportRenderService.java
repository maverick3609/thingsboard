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
package org.thingsboard.server.service.report;

import org.thingsboard.server.common.data.job.task.ReportTask;
import org.thingsboard.server.common.data.report.ReportData;
import org.thingsboard.server.common.data.report.TbReportFormat;

/**
 * One render engine per {@link TbReportFormat} (design spec §6.1): given a fully-populated
 * {@link ReportTask}, produces the rendered {@link ReportData} for that task's report template.
 * <p>
 * PE names this interface {@code ReportService}. Renamed {@code TbReportRenderService} here because
 * the dao layer already owns {@code org.thingsboard.server.dao.report.ReportService} (Task 8) and a
 * single Java package can't declare two top-level types with the same simple name. {@link
 * TbReportService} keeps the PE dispatcher name — it picks a {@code TbReportRenderService} by
 * format and persists the result.
 */
public interface TbReportRenderService {

    ReportData generateReport(ReportTask task);

    TbReportFormat getFormat();

}
