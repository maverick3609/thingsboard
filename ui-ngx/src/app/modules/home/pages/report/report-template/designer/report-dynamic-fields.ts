// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
