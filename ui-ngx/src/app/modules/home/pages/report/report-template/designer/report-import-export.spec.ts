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

// Plain instantiation (no TestBed), matching this designer's established convention
// (report-configuration.serializer.spec.ts, report-template-editor.component.spec.ts,
// report-preview.component.spec.ts's URL.createObjectURL/revokeObjectURL spy idiom for the
// download-side test below). ReportImportExportService has ZERO constructor dependencies (see
// report-import-export.ts's header - it deliberately does not inject the platform
// ImportExportService, which would drag a pre-existing ThingsBoard circular-import defect into
// this karma bundle), so `new ReportImportExportService()` is enough. buildExportTemplate and
// parseImportedReportTemplate (the parse/validate core importTemplate() wraps in FileReader
// plumbing) are exercised directly - pure functions/data, no DOM at all - for the majority of
// specs; only the "exportTemplate (download)" and "importTemplate (FileReader wiring)" blocks
// touch real Web APIs (Blob/URL/an anchor element, and a real File - all real, available under
// ChromeHeadless).
import { fakeAsync, tick } from '@angular/core/testing';
import {
  buildExportTemplate,
  parseImportedReportTemplate,
  ReportImportExportService,
  sanitizeFilename
} from './report-import-export';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { newPdfReportTemplateConfig, PdfReportTemplateConfig } from '@shared/models/report-configuration.models';
import { serialize } from './report-configuration.serializer';

describe('report-import-export', () => {
  let service: ReportImportExportService;

  beforeEach(() => {
    service = new ReportImportExportService();
  });

  function reportTemplate(): ReportTemplate {
    return { name: 'Q3 report', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT } as ReportTemplate;
  }

  describe('buildExportTemplate', () => {
    it('builds {name, format, type, configuration} from the LIVE config', () => {
      const rt = reportTemplate();
      const config = newPdfReportTemplateConfig();
      config.components = [{ type: 'HEADING', value: 'Q3' }];

      const result = buildExportTemplate(rt, config);

      expect(result).toEqual({
        name: 'Q3 report',
        format: 'PDF',
        type: ReportTemplateType.REPORT,
        configuration: serialize(config)
      });
    });

    it('uses the LIVE config.format (authoritative), not a stale reportTemplate.format', () => {
      const rt = { ...reportTemplate(), format: TbReportFormat.CSV } as ReportTemplate;
      const config = newPdfReportTemplateConfig(); // live editor config is still PDF-shaped

      expect(buildExportTemplate(rt, config).format).toBe('PDF');
    });
  });

  describe('ReportImportExportService.exportTemplate (download)', () => {
    beforeEach(() => {
      spyOn(URL, 'createObjectURL').and.returnValue('blob:mock-url');
      spyOn(URL, 'revokeObjectURL');
      spyOn(HTMLAnchorElement.prototype, 'click');
    });

    // C2 final-review Fix M1: revocation is now deferred via setTimeout(..., 0) (mirrors the
    // platform's ImportExportService.downloadFile), so this test needs fakeAsync/tick to observe
    // it - same convention as report-preview.component.spec.ts's debounce tests in this directory.
    it('downloads a `<name>.json` file built from the live config and returns the built object', fakeAsync(() => {
      const rt = reportTemplate();
      const config = newPdfReportTemplateConfig();

      const result = service.exportTemplate(rt, config);

      expect(result).toEqual(buildExportTemplate(rt, config));
      expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
      const blobArg = (URL.createObjectURL as jasmine.Spy).calls.mostRecent().args[0] as Blob;
      expect(blobArg.type).toBe('application/json');
      expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledTimes(1);
      const anchor = (HTMLAnchorElement.prototype.click as jasmine.Spy).calls.mostRecent().object as HTMLAnchorElement;
      // C2 final-review Fix M2: the name is now sanitized before becoming a filename - the space in
      // 'Q3 report' is one of the characters sanitizeFilename() replaces, same as the platform's
      // prepareFilename() would (see the dedicated sanitizeFilename specs below for the
      // filesystem-hostile-character case this fix actually targets).
      expect(anchor.download).toBe('Q3_report.json');
      // Not yet revoked synchronously (Fix M1) ...
      expect(URL.revokeObjectURL).not.toHaveBeenCalled();
      tick(0);
      // ... but is once the deferred setTimeout(0) fires.
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
    }));

    // C2 final-review Fix M2: a template name containing filesystem-hostile characters (path
    // separators, a colon, etc.) must not produce a broken/mangled download filename.
    it('sanitizes filesystem-hostile characters out of the download filename', fakeAsync(() => {
      const rt = { ...reportTemplate(), name: 'a/b:c' } as ReportTemplate;
      const config = newPdfReportTemplateConfig();

      service.exportTemplate(rt, config);

      const anchor = (HTMLAnchorElement.prototype.click as jasmine.Spy).calls.mostRecent().object as HTMLAnchorElement;
      expect(anchor.download).toBe('a_b_c.json');
      tick(0);
    }));
  });

  describe('sanitizeFilename', () => {
    it('replaces filesystem-hostile characters and whitespace with underscores (mirrors the platform prepareFilename regex)', () => {
      expect(sanitizeFilename('a/b:c')).toBe('a_b_c');
      expect(sanitizeFilename('a\\b<c>d"e|f?g*h')).toBe('a_b_c_d_e_f_g_h');
      expect(sanitizeFilename('Q3 report')).toBe('Q3_report');
    });

    it('leaves an already-safe name untouched', () => {
      expect(sanitizeFilename('Q3-report_2026')).toBe('Q3-report_2026');
    });
  });

  describe('parseImportedReportTemplate', () => {
    it('parses valid JSON into {name, format, type, config} with a components array', () => {
      const exported = {
        name: 'Q3 report',
        format: 'PDF',
        type: 'REPORT',
        configuration: { format: 'PDF', components: [{ type: 'HEADING', value: 'Q3' }] }
      };

      const result = parseImportedReportTemplate(JSON.stringify(exported));

      expect(result.name).toBe('Q3 report');
      expect(result.format).toBe('PDF');
      expect(result.type).toBe('REPORT');
      expect(Array.isArray(result.config.components)).toBe(true);
      expect(result.config.components).toEqual([{ type: 'HEADING', value: 'Q3' }]);
    });

    it('throws a clear validation error when "format" is missing (brief\'s explicit case)', () => {
      const noFormat = { name: 'x', type: 'REPORT', configuration: { components: [] } };

      expect(() => parseImportedReportTemplate(JSON.stringify(noFormat))).toThrowError(/format/);
    });

    it('throws a clear validation error when "format" is present but not PDF/CSV', () => {
      const badFormat = { name: 'x', format: 'XLS', type: 'REPORT', configuration: { components: [] } };

      expect(() => parseImportedReportTemplate(JSON.stringify(badFormat))).toThrowError(/format/);
    });

    it('throws a clear validation error on malformed JSON', () => {
      expect(() => parseImportedReportTemplate('{not valid json')).toThrowError(/Invalid report template file/);
    });

    it('throws a clear validation error when "configuration" is missing', () => {
      const noConfig = { name: 'x', format: 'PDF', type: 'REPORT' };

      expect(() => parseImportedReportTemplate(JSON.stringify(noConfig))).toThrowError(/configuration/);
    });

    it('round-trips: parseImportedReportTemplate(JSON.stringify(buildExportTemplate(rt, config))) reproduces the config', () => {
      const rt = reportTemplate();
      const config = newPdfReportTemplateConfig();
      config.components = [{ type: 'DIVIDER', color: '#000' }];
      (config as PdfReportTemplateConfig).entityAliases = [{ id: 'a1', alias: 'Devices', filter: {} } as any];

      const exported = buildExportTemplate(rt, config);
      const imported = parseImportedReportTemplate(JSON.stringify(exported));

      expect(imported.config).toEqual(serialize(config));
      expect(imported.name).toBe(rt.name);
      expect(imported.format).toBe(config.format);
      expect(imported.type).toBe(rt.type);
    });
  });

  describe('importTemplate (FileReader wiring)', () => {
    it('reads a File, parses + validates its content, and emits the imported template', (done) => {
      const json = JSON.stringify({
        name: 'From file', format: 'PDF', type: 'REPORT',
        configuration: { format: 'PDF', components: [] }
      });
      const file = new File([json], 'template.json', { type: 'application/json' });

      service.importTemplate(file).subscribe({
        next: (result) => {
          expect(result.name).toBe('From file');
          expect(result.config.components).toEqual([]);
          done();
        },
        error: done.fail
      });
    });

    it('emits a validation error for a file whose content has no "format"', (done) => {
      const file = new File([JSON.stringify({ name: 'x' })], 'template.json', { type: 'application/json' });

      service.importTemplate(file).subscribe({
        next: () => done.fail('expected an error, got a value'),
        error: (err) => {
          expect(err).toBeTruthy();
          expect(err.message).toMatch(/format/);
          done();
        }
      });
    });
  });
});
