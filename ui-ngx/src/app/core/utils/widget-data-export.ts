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

// Generic "Export widget data" writer (PE port, BUG 2 — Timeseries/Entities/Alarms tables had no export
// option). Table shape (columns/rows) is sourced generically from the widget host — see
// widget-export-data-source.ts. This file only turns a {columns, rows} table into a CSV / XLS / XLSX file
// and triggers a browser download. No upstream csv/xlsx/file-saver dependency exists in CE (see the BUG 2
// investigation report), so this is net-new. Deliberately does NOT import ImportExportService — it has a
// pre-existing DialogService circular-import that crashes bundles.
//
// Format choices (see the BUG 2 report for the PE evidence this mirrors):
// - CSV: plain text, RFC4180-ish quoting.
// - XLS: PE's "XLS" is most likely the classic HTML-table-as-.xls (mime application/vnd.ms-excel) — simplest
//   Excel-openable equivalent; BIFF internals were not traceable from the minified PE bundle, so this is an
//   accepted equivalent, not a byte-identical port.
// - XLSX: a real minimal OOXML package (inline-string cells, one sheet, no xl/styles.xml — no cell references
//   a style index, so omitting the styles part stays spec-valid), zipped with JSZip (already bundled — see
//   ui-ngx/src/app/shared/import-export/import-export.service.ts for the same dynamic `import('jszip')`
//   pattern this file reuses).
//
// Security — formula injection: every cell is passed through neutralizeFormulaInjection before CSV/XML/HTML
// encoding. This mirrors application/src/main/java/.../service/report/util/CsvUtils.java#neutralizeFormula
// (same trigger charset incl. CJK full-width leads, same leading-whitespace scan). Table cell values are
// attacker-influenceable (device telemetry, entity/alarm names), and all three formats are opened in a
// spreadsheet application, so the OWASP CSV-injection guard is applied uniformly to CSV *and* XLS/XLSX, not
// just CSV — a formula lead is just as live in an HTML-sourced .xls or a real .xlsx cell as it is in a .csv.

export enum WidgetExportType {
  csv = 'csv',
  xls = 'xls',
  xlsx = 'xlsx'
}

export const widgetExportTypeTranslations = new Map<WidgetExportType, string>(
  [
    [WidgetExportType.csv, 'widget.export-to-csv'],
    [WidgetExportType.xls, 'widget.export-to-excel'],
    [WidgetExportType.xlsx, 'widget.export-to-excel-xlsx']
  ]
);

export type WidgetExportCellValue = string | number | boolean | null | undefined;

export interface WidgetExportTable {
  columns: string[];
  rows: WidgetExportCellValue[][];
}

const MIME_TYPES: {[format in WidgetExportType]: string} = {
  [WidgetExportType.csv]: 'text/csv;charset=utf-8',
  [WidgetExportType.xls]: 'application/vnd.ms-excel',
  [WidgetExportType.xlsx]: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
};

const FILE_EXTENSIONS: {[format in WidgetExportType]: string} = {
  [WidgetExportType.csv]: 'csv',
  [WidgetExportType.xls]: 'xls',
  [WidgetExportType.xlsx]: 'xlsx'
};

/**
 * OWASP CSV/formula-injection guard — mirrors CsvUtils.neutralizeFormula (application/.../service/report/util,
 * Inferrix hardening; PE has no such guard). Same trigger set (ASCII `= + - @` plus the CJK full-width
 * variants a locale-aware Excel normalises before evaluating) and the leading-whitespace scan (closes
 * importers that strip a leading space/control char before formula detection). Prefixes the *whole* cell with
 * a literal single quote so a spreadsheet renders it as text instead of evaluating it. Exported for direct
 * unit testing.
 */
export const FORMULA_LEAD_CHARS = '=+-@＝＋－＠';

// Matches Java Character.isWhitespace for the leading scan: JS /\s/ covers tab/LF/VT/FF/CR/space and the
// Unicode space separators, but NOT the C0 information separators U+001C–U+001F (FS/GS/RS/US), which Java
// treats as whitespace and which some spreadsheet importers strip before formula detection — include them
// so a `<U+001C>=cmd` lead is neutralized here exactly as the backend CsvUtils#neutralizeFormula does.
const isLeadWhitespace = (c: string): boolean =>
  /\s/.test(c) || (c.charCodeAt(0) >= 0x1C && c.charCodeAt(0) <= 0x1F);

export function neutralizeFormulaInjection(cell: string): string {
  if (!cell) {
    return cell;
  }
  let i = 0;
  while (i < cell.length && isLeadWhitespace(cell.charAt(i))) {
    i++;
  }
  if (i < cell.length && FORMULA_LEAD_CHARS.indexOf(cell.charAt(i)) >= 0) {
    return `'${cell}`;
  }
  return cell;
}

function cellToText(value: WidgetExportCellValue): string {
  if (value === null || value === undefined) {
    return '';
  }
  return typeof value === 'string' ? value : String(value);
}

function safeCellText(value: WidgetExportCellValue): string {
  return neutralizeFormulaInjection(cellToText(value));
}

// ---- CSV ----

function csvEscape(text: string): string {
  if (/["\n\r,]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

// UTF-8 byte-order-mark (U+FEFF) so Excel renders non-ASCII CSV content correctly instead of mis-detecting the
// encoding. Built via fromCharCode (rather than a string literal) so the invisible character is unambiguous
// in source/diffs.
export const CSV_UTF8_BOM = String.fromCharCode(0xFEFF);

export function widgetTableToCsv(table: WidgetExportTable): string {
  const allRows = [table.columns, ...table.rows];
  return allRows
    .map(row => row.map(cell => csvEscape(safeCellText(cell))).join(','))
    .join('\r\n');
}

// ---- XLS (HTML table — PE-equivalent; see file header) ----

function htmlEscape(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function widgetTableToHtmlXls(table: WidgetExportTable): string {
  const headerCells = table.columns.map(col => `<th>${htmlEscape(safeCellText(col))}</th>`).join('');
  const bodyRows = table.rows
    .map(row => `<tr>${row.map(cell => `<td>${htmlEscape(safeCellText(cell))}</td>`).join('')}</tr>`)
    .join('');
  return '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel" ' +
    'xmlns="http://www.w3.org/TR/REC-html40">' +
    '<head><meta charset="UTF-8"></head>' +
    `<body><table border="1"><thead><tr>${headerCells}</tr></thead><tbody>${bodyRows}</tbody></table></body></html>`;
}

// ---- XLSX (minimal real OOXML package) ----

function xmlEscape(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function columnLetter(index: number): string {
  let n = index + 1;
  let letters = '';
  while (n > 0) {
    const rem = (n - 1) % 26;
    letters = String.fromCharCode(65 + rem) + letters;
    n = Math.floor((n - 1) / 26);
  }
  return letters;
}

function xlsxCellXml(value: WidgetExportCellValue, colIndex: number, rowNumber: number): string {
  const ref = `${columnLetter(colIndex)}${rowNumber}`;
  if (typeof value === 'number' && isFinite(value)) {
    return `<c r="${ref}"><v>${value}</v></c>`;
  }
  const text = xmlEscape(safeCellText(value));
  return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${text}</t></is></c>`;
}

function xlsxRowXml(cells: WidgetExportCellValue[], rowNumber: number): string {
  const cellsXml = cells.map((cell, colIndex) => xlsxCellXml(cell, colIndex, rowNumber)).join('');
  return `<row r="${rowNumber}">${cellsXml}</row>`;
}

const CONTENT_TYPES_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
  '<Default Extension="xml" ContentType="application/xml"/>' +
  '<Override PartName="/xl/workbook.xml" ' +
  'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
  '<Override PartName="/xl/worksheets/sheet1.xml" ' +
  'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
  '</Types>';

const ROOT_RELS_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" ' +
  'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" ' +
  'Target="xl/workbook.xml"/>' +
  '</Relationships>';

const WORKBOOK_RELS_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" ' +
  'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" ' +
  'Target="worksheets/sheet1.xml"/>' +
  '</Relationships>';

const WORKBOOK_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
  'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
  '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>' +
  '</workbook>';

export function widgetTableToXlsxSheetXml(table: WidgetExportTable): string {
  const rowsXml = [table.columns, ...table.rows]
    .map((row, index) => xlsxRowXml(row, index + 1))
    .join('');
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    `<sheetData>${rowsXml}</sheetData>` +
    '</worksheet>';
}

export async function widgetTableToXlsxBlob(table: WidgetExportTable): Promise<Blob> {
  const jszipModule = await import('jszip');
  const JSZip = jszipModule.default;
  const zip = new JSZip();
  zip.file('[Content_Types].xml', CONTENT_TYPES_XML);
  zip.folder('_rels').file('.rels', ROOT_RELS_XML);
  const xl = zip.folder('xl');
  xl.file('workbook.xml', WORKBOOK_XML);
  xl.folder('_rels').file('workbook.xml.rels', WORKBOOK_RELS_XML);
  xl.folder('worksheets').file('sheet1.xml', widgetTableToXlsxSheetXml(table));
  return zip.generateAsync({type: 'blob', mimeType: MIME_TYPES[WidgetExportType.xlsx]});
}

// ---- Download trigger ----

function sanitizeFilename(name: string): string {
  const trimmed = (name || 'widget').trim().replace(/[\\/:*?"<>|]/g, '_');
  return trimmed.length ? trimmed : 'widget';
}

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function exportWidgetTableData(table: WidgetExportTable, baseFilename: string,
                                             format: WidgetExportType): Promise<void> {
  const filename = `${sanitizeFilename(baseFilename)}.${FILE_EXTENSIONS[format]}`;
  switch (format) {
    case WidgetExportType.xls:
      triggerBlobDownload(new Blob([widgetTableToHtmlXls(table)], {type: MIME_TYPES[format]}), filename);
      break;
    case WidgetExportType.xlsx:
      triggerBlobDownload(await widgetTableToXlsxBlob(table), filename);
      break;
    case WidgetExportType.csv:
    default: {
      // Leading BOM so Excel renders non-ASCII (UTF-8) CSV content correctly.
      const csvBlob = new Blob([CSV_UTF8_BOM + widgetTableToCsv(table)], {type: MIME_TYPES[WidgetExportType.csv]});
      triggerBlobDownload(csvBlob, filename);
      break;
    }
  }
}
