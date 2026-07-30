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

import {
  exportWidgetTableData,
  neutralizeFormulaInjection,
  WidgetExportTable,
  WidgetExportType,
  widgetTableToCsv,
  widgetTableToHtmlXls,
  widgetTableToXlsxBlob,
  widgetTableToXlsxSheetXml
} from './widget-data-export';

describe('widget-data-export', () => {

  // Mirrors application/src/test/java/.../service/report/util/CsvUtilsTest.java's cases 1:1 — same trigger
  // charset and leading-whitespace scan ported to TypeScript.
  describe('neutralizeFormulaInjection', () => {
    it('neutralizes each formula trigger with a leading quote', () => {
      expect(neutralizeFormulaInjection('=1+1')).toBe('\'=1+1');
      expect(neutralizeFormulaInjection('+1')).toBe('\'+1');
      expect(neutralizeFormulaInjection('-1')).toBe('\'-1');
      expect(neutralizeFormulaInjection('@SUM(A1)')).toBe('\'@SUM(A1)');
      expect(neutralizeFormulaInjection('\t=1+1')).toBe('\'\t=1+1');
      expect(neutralizeFormulaInjection('\r=1+1')).toBe('\'\r=1+1');
    });

    it('neutralizes whitespace-prefixed and full-width formula leads', () => {
      expect(neutralizeFormulaInjection(' =1+1')).toBe('\' =1+1');
      expect(neutralizeFormulaInjection('\n=1+1')).toBe('\'\n=1+1');
      expect(neutralizeFormulaInjection('  @SUM(A1)')).toBe('\'  @SUM(A1)');
      expect(neutralizeFormulaInjection('＝1+1')).toBe('\'＝1+1');
      expect(neutralizeFormulaInjection('＋1')).toBe('\'＋1');
    });

    it('leaves safe values unchanged', () => {
      expect(neutralizeFormulaInjection('Device A')).toBe('Device A');
      expect(neutralizeFormulaInjection('42.5')).toBe('42.5');
      expect(neutralizeFormulaInjection('a=b')).toBe('a=b');
      expect(neutralizeFormulaInjection(' hello')).toBe(' hello');
      expect(neutralizeFormulaInjection('   ')).toBe('   ');
      expect(neutralizeFormulaInjection('\tfoo')).toBe('\tfoo');
      expect(neutralizeFormulaInjection('')).toBe('');
      expect(neutralizeFormulaInjection(null)).toBeNull();
    });
  });

  const table: WidgetExportTable = {
    columns: ['Entity', 'Timestamp', 'Temperature'],
    rows: [
      ['Device A', '2026-01-01 00:00:00', 21.5],
      ['Device B', '2026-01-01 00:01:00', null]
    ]
  };

  describe('widgetTableToCsv', () => {
    it('emits a header row and one row per data row, CRLF-separated', () => {
      const csv = widgetTableToCsv(table);
      const lines = csv.split('\r\n');
      expect(lines.length).toBe(3);
      expect(lines[0]).toBe('Entity,Timestamp,Temperature');
      expect(lines[1]).toBe('Device A,2026-01-01 00:00:00,21.5');
      expect(lines[2]).toBe('Device B,2026-01-01 00:01:00,');
    });

    it('quotes cells containing commas, quotes or newlines', () => {
      const csv = widgetTableToCsv({columns: ['a,b', 'c"d', 'e\nf'], rows: []});
      expect(csv).toBe('"a,b","c""d","e\nf"');
    });

    it('neutralizes a formula-leading cell before CSV-structural escaping', () => {
      const csv = widgetTableToCsv({columns: ['Name'], rows: [['=cmd|calc']]});
      expect(csv).toContain('\'=cmd|calc');
      expect(csv.split('\r\n')[1].startsWith('=')).toBeFalse();
    });
  });

  describe('widgetTableToHtmlXls', () => {
    it('renders an HTML table with escaped header and data cells', () => {
      const html = widgetTableToHtmlXls({columns: ['<Name>'], rows: [['A & B']]});
      expect(html).toContain('<th>&lt;Name&gt;</th>');
      expect(html).toContain('<td>A &amp; B</td>');
      expect(html).toContain('<table');
    });

    it('neutralizes a formula-leading cell (leading quote HTML-entity-escaped, still literal text on decode)', () => {
      const html = widgetTableToHtmlXls({columns: ['Name'], rows: [['=cmd']]});
      expect(html).toContain('&#39;=cmd');
      expect(html).not.toContain('<td>=cmd</td>');
    });
  });

  describe('widgetTableToXlsxSheetXml', () => {
    it('emits a worksheet with one row per table row (header included) and lettered cell refs', () => {
      const xml = widgetTableToXlsxSheetXml(table);
      expect(xml).toContain('<worksheet');
      expect(xml).toContain('<row r="1">');
      expect(xml).toContain('<c r="A1" t="inlineStr"><is><t xml:space="preserve">Entity</t></is></c>');
      expect(xml).toContain('<row r="2">');
      expect(xml).toContain('<c r="A2" t="inlineStr"><is><t xml:space="preserve">Device A</t></is></c>');
      // Numeric cell values are emitted as real numeric cells, not inline strings.
      expect(xml).toContain('<c r="C2"><v>21.5</v></c>');
    });

    it('XML-escapes inline string content', () => {
      const xml = widgetTableToXlsxSheetXml({columns: ['<a & "b">'], rows: []});
      expect(xml).toContain('&lt;a &amp; &quot;b&quot;&gt;');
      expect(xml).not.toContain('<a & "b">');
    });

    it('neutralizes a formula-leading cell (leading quote XML-entity-escaped, still literal text on decode)', () => {
      const xml = widgetTableToXlsxSheetXml({columns: ['Name'], rows: [['=cmd']]});
      expect(xml).toContain('&apos;=cmd');
      expect(xml).not.toContain('<t xml:space="preserve">=cmd</t>');
    });
  });

  describe('widgetTableToXlsxBlob', () => {
    it('produces a real OOXML zip package with the expected parts', async () => {
      const blob = await widgetTableToXlsxBlob(table);
      expect(blob.type).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');

      const jszipModule = await import('jszip');
      const JSZip = jszipModule.default;
      const zip = await JSZip.loadAsync(blob);

      expect(zip.file('[Content_Types].xml')).toBeTruthy();
      expect(zip.file('_rels/.rels')).toBeTruthy();
      expect(zip.file('xl/workbook.xml')).toBeTruthy();
      expect(zip.file('xl/_rels/workbook.xml.rels')).toBeTruthy();
      expect(zip.file('xl/worksheets/sheet1.xml')).toBeTruthy();

      const sheetXml = await zip.file('xl/worksheets/sheet1.xml').async('string');
      expect(sheetXml).toContain('Device A');
      expect(sheetXml).toContain('<c r="C2"><v>21.5</v></c>');

      const contentTypesXml = await zip.file('[Content_Types].xml').async('string');
      expect(contentTypesXml).toContain('spreadsheetml.sheet.main+xml');
    });
  });

  describe('exportWidgetTableData', () => {
    let createObjectURLSpy: jasmine.Spy;
    let clickSpy: jasmine.Spy;

    beforeEach(() => {
      createObjectURLSpy = spyOn(URL, 'createObjectURL').and.returnValue('blob:mock-url');
      spyOn(URL, 'revokeObjectURL');
      clickSpy = spyOn(HTMLAnchorElement.prototype, 'click');
    });

    it('builds a CSV blob and triggers a download for the csv format', async () => {
      await exportWidgetTableData(table, 'My Widget', WidgetExportType.csv);
      expect(createObjectURLSpy).toHaveBeenCalled();
      const blob = createObjectURLSpy.calls.mostRecent().args[0] as Blob;
      expect(blob.type).toContain('text/csv');
      expect(clickSpy).toHaveBeenCalled();
    });

    it('builds an xls blob for the xls format', async () => {
      await exportWidgetTableData(table, 'My Widget', WidgetExportType.xls);
      const blob = createObjectURLSpy.calls.mostRecent().args[0] as Blob;
      expect(blob.type).toBe('application/vnd.ms-excel');
    });

    it('builds an xlsx blob for the xlsx format', async () => {
      await exportWidgetTableData(table, 'My Widget', WidgetExportType.xlsx);
      const blob = createObjectURLSpy.calls.mostRecent().args[0] as Blob;
      expect(blob.type).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    });
  });
});
