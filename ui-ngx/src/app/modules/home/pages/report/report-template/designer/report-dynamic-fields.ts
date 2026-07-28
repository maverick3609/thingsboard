///
/// Copyright © 2016-2026 The Inferrix Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

// C2 Task 15B: the `${...}` placeholder tokens the server-side renderer substitutes into HEADING/
// RICH_TEXT `value` text (application/.../service/report/AbstractReportService.java's per-entity
// variables map + ThymeleafUtil's preserved page-counter tokens) - offered as an "Insert dynamic
// field" menu on both panels so the user doesn't have to memorize/hand-type them. `${dataKeyLabel}`
// is table-cell context only (populated per data key, not per-component) and is deliberately excluded
// here - it has no meaning on a standalone HEADING/RICH_TEXT component.
export interface ReportDynamicField {
  token: string;
  labelKey: string;
}

export const REPORT_DYNAMIC_FIELDS: ReportDynamicField[] = [
  { token: '${pageNumber}', labelKey: 'report.designer.dynamic-field-page-number' },
  { token: '${totalPages}', labelKey: 'report.designer.dynamic-field-total-pages' },
  { token: '${reportCreatedTime}', labelKey: 'report.designer.dynamic-field-created-time' },
  { token: '${entityName}', labelKey: 'report.designer.dynamic-field-entity-name' },
  { token: '${entityLabel}', labelKey: 'report.designer.dynamic-field-entity-label' },
  { token: '${id}', labelKey: 'report.designer.dynamic-field-id' }
];
