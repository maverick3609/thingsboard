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

// Import/Export of a report template to/from a standalone JSON file (C2 Task 17), wired into the
// designer shell's toolbar (report-template-editor.component.ts). Import is self-contained
// (FileReader + JSON.parse + validation) and deliberately does NOT save: it only hands the shell
// the pieces needed to replace its in-memory reportTemplate/config so the user can review (and
// further edit) before hitting Save.
//
// COORDINATOR OVERRIDE (2026-07-28, supersedes the original "reuse ImportExportService.exportJson"
// design note): this file does NOT inject or import the platform ImportExportService
// (shared/import-export/import-export.service.ts), even though its exportJson is public and would
// have been a DRY reuse. Root cause: ImportExportService transitively reaches
// @core/services/dialog.service.ts, which has a PRE-EXISTING circular import with dialog
// components it opens (confirmed via isolated probe specs: EntityLimitExceededDialogComponent,
// and one layer deeper once that's dodged, EntityAliasDialogComponent - both inject the very
// manager service that statically imports them). Importing ImportExportService into ANY karma test
// bundle throws "Cannot access 'X' before initialization" (a TDZ ReferenceError) at module-load
// time, purely from webpack's module concatenation order - unrelated to anything in this feature.
// Confirmed with a 2-line probe containing ONLY `import { DialogService } from
// '@core/services/dialog.service'`: identical crash, zero involvement of this file's code; no spec
// anywhere in ui-ngx currently imports ImportExportService, for exactly this reason. Fixing that
// cycle is TB-core surgery outside this task's scope, so instead this file inlines the same
// Blob+anchor download ImportExportService.exportJson uses internally (import-export.service.ts's
// private downloadFile()) - a well-known, dependency-free ~6-line pattern - keeping this file (and
// everything that imports it, including the shell) free of the ImportExportService/DialogService
// import chain entirely, so it - and report-template-editor.component.spec.ts - run under karma.
//
// The exported/imported root shape mirrors the 3 ReportTemplate fields this designer edits
// (name/format/type) plus `configuration` = serialize(config) - the SAME byte-fidelity tree
// save() persists (report-configuration.serializer.ts). `format` is taken from the LIVE config
// (config.format), not the possibly-stale reportTemplate.format, since config is the in-memory
// source of truth for unsaved edits (report-template-editor.component.ts's `config` field) -
// see task-17-interfaces.md.
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { deserialize, serialize } from '@home/pages/report/report-template/designer/report-configuration.serializer';

// The root export/import file shape - `configuration` is the serialize()d config tree, exactly
// what save() persists into ReportTemplate.configuration.
export interface ReportTemplateExport {
  name: string;
  format: 'PDF' | 'CSV';
  type: ReportTemplateType;
  configuration: any;
}

// What the shell applies on a successful import: the 3 root ReportTemplate fields it owns, plus
// the deserialized config tree ready to replace `this.config` as-is.
export interface ImportedReportTemplate {
  name: string;
  format: TbReportFormat;
  type: ReportTemplateType;
  config: ReportTemplateConfigModel;
}

// Pure builder - no I/O, no DOM. Kept separate from the download side effect (below) so a spec can
// assert the built shape (and round-trip it through parseImportedReportTemplate) without
// exercising any Blob/anchor/DOM plumbing at all.
export function buildExportTemplate(reportTemplate: ReportTemplate, config: ReportTemplateConfigModel): ReportTemplateExport {
  return {
    name: reportTemplate.name,
    format: config.format,
    type: reportTemplate.type,
    configuration: serialize(config)
  };
}

@Injectable()
export class ReportImportExportService {

  // Builds the export object from the LIVE editor config (unsaved edits included - NOT the stale
  // reportTemplate.configuration) and downloads it as a `<name>.json` file. Also returns the built
  // object (besides the download side effect) so a caller can round-trip it straight into
  // parseImportedReportTemplate without needing a real File.
  exportTemplate(reportTemplate: ReportTemplate, config: ReportTemplateConfigModel): ReportTemplateExport {
    const data = buildExportTemplate(reportTemplate, config);
    this.downloadTemplate(data, reportTemplate.name);
    return data;
  }

  // FileReader -> parseImportedReportTemplate. Never saves; the caller (shell) decides what to do
  // with the result. Errors from a bad file (unreadable, malformed JSON, failed validation) are
  // surfaced as an Observable error, not a thrown exception, since the read itself is async.
  importTemplate(file: File): Observable<ImportedReportTemplate> {
    return new Observable<ImportedReportTemplate>(subscriber => {
      const reader = new FileReader();
      reader.onload = () => {
        try {
          subscriber.next(parseImportedReportTemplate(reader.result as string));
          subscriber.complete();
        } catch (e) {
          subscriber.error(e);
        }
      };
      reader.onerror = () => subscriber.error(new Error('Failed to read the selected file.'));
      reader.readAsText(file);
    });
  }

  // Inline Blob+anchor download - see file header for why this doesn't reuse
  // ImportExportService.exportJson despite it being public.
  private downloadTemplate(data: any, filename: string): void {
    const json = JSON.stringify(data, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${filename}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }
}

// Parses + validates a report template export JSON string. Split out from importTemplate() so
// specs (and the round-trip DoD) can feed a JSON string directly, without faking File/FileReader
// for every case. Reuses report-configuration.serializer.ts's own deserialize (does NOT
// re-implement its shape checks) - the one extra rule enforced here is the root `format`
// discriminator, which deserialize itself never checks (it only defaults `components`).
export function parseImportedReportTemplate(json: string): ImportedReportTemplate {
  try {
    const parsed = JSON.parse(json);
    const format = parsed?.format;
    if (format !== TbReportFormat.PDF && format !== TbReportFormat.CSV) {
      throw new Error('missing or unsupported "format" (expected PDF or CSV)');
    }
    if (parsed.configuration === undefined || parsed.configuration === null) {
      throw new Error('missing "configuration"');
    }
    const config = deserialize(parsed.configuration);
    // Belt-and-suspenders: deserialize() already defaults `components` to [] whenever
    // parsed.configuration is a valid object (report-configuration.serializer.spec.ts), so this is
    // currently unreachable for realistic inputs - kept as an explicit assertion of the contract
    // this function promises callers, so a future deserialize change can't silently break it here.
    if (!Array.isArray(config.components)) {
      throw new Error('invalid "configuration" (missing components array)');
    }
    return {
      name: parsed.name,
      format,
      type: parsed.type,
      config
    };
  } catch (e) {
    throw new Error(`Invalid report template file: ${e.message}`);
  }
}
