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

import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { TranslateService } from '@ngx-translate/core';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { ReportService } from '@core/http/report.service';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import {
  ReportComponent,
  ReportTemplateConfigModel,
  newCsvReportTemplateConfig,
  newPdfReportTemplateConfig
} from '@shared/models/report-configuration.models';
import { deserialize, serialize } from '@home/pages/report/report-template/designer/report-configuration.serializer';
import { ReportPageSettings } from '@home/pages/report/report-template/designer/report-page-settings.component';
import { IAliasController } from '@core/api/widget-api.models';
import { createReportAliasController } from '@home/pages/report/report-template/designer/data/report-alias-controller';
import { ReportImportExportService } from '@home/pages/report/report-template/designer/report-import-export';

// PDF-only projection of config's page-level fields for tb-report-page-settings (T5); null for a
// CSV config, which has no page settings - the shell hides the panel rather than show one whose
// edits would have nowhere to go.
function toPageSettings(config: ReportTemplateConfigModel): ReportPageSettings | null {
  return config?.format === 'PDF' ? {
    pageSize: config.pageSize,
    pageOrientation: config.pageOrientation,
    pageMargins: config.pageMargins,
    pageBackground: config.pageBackground,
    namePattern: config.namePattern,
    timeDataPattern: config.timeDataPattern
  } : null;
}

// Router-state payload the templates list hands to the 'new' route - see ngOnInit below. `config`
// is only set on the import path, where the file already carries a full (deserialized) config tree.
interface SeededReportTemplate {
  name?: string;
  format?: TbReportFormat;
  type?: ReportTemplateType;
  description?: string;
  config?: ReportTemplateConfigModel;
}

// A blank config of the shape the chosen format requires: the two variants are structurally
// different (only PDF carries page/header/footer fields), so format has to be decided before the
// designer opens - which is exactly what the add dialog is for.
function newTemplateConfig(format: TbReportFormat): ReportTemplateConfigModel {
  return format === TbReportFormat.CSV ? newCsvReportTemplateConfig() : newPdfReportTemplateConfig();
}

// Full-page three-pane designer shell (design spec R2c "C1"): a toolbar (back/name/save/edit-preview
// toggle) over a left/center/right body. T3 wired load/save + the placeholder panes; T5 fills the
// right pane's page-settings branch (selected === null). The palette (left)/canvas (center) and
// component-config (right, selected !== null) panes are filled in by T6/T8-T11, and the preview
// overlay behind the toggle by T7 - they all bind against `config`/`selected`.
// Reachable at both '/reporting/templates/new' and '/reporting/templates/:reportTemplateId'
// (report-routing.module.ts); the 'new' route has no reportTemplateId param, so a missing param is
// treated the same as the literal 'new' segment.
@Component({
  selector: 'tb-report-template-editor',
  templateUrl: './report-template-editor.component.html',
  styleUrls: ['./report-template-editor.component.scss'],
  standalone: false
})
export class ReportTemplateEditorComponent implements OnInit {

  isAdd: boolean;
  // Exposed as a field (was a local ngOnInit const through Task 11) so Task 13's SUB_REPORT panel
  // can receive it as `[currentTemplateId]` - a template must not be able to sub-report itself.
  reportTemplateId: string | null;
  reportTemplate: ReportTemplate;
  config: ReportTemplateConfigModel;
  selected: ReportComponent | null = null;
  viewMode: 'edit' | 'preview' = 'edit';
  pageSettings: ReportPageSettings | null = null;
  // Bounded IAliasController shim (Task 9) backing tb-entity-alias-select/tb-filter-select inside
  // Task 11's table panels - built once here, lazily reading `this.config` on every call, so it
  // stays valid across the async (existing-template) load path below.
  aliasController: IAliasController;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private reportService: ReportService,
              private reportImportExport: ReportImportExportService,
              private store: Store<AppState>,
              private translate: TranslateService) {
  }

  ngOnInit(): void {
    const routeReportTemplateId = this.route.snapshot.paramMap.get('reportTemplateId');
    this.isAdd = !routeReportTemplateId || routeReportTemplateId === 'new';
    // null (not the literal 'new' route segment) in add-mode - a not-yet-saved template has no real
    // id for Task 13's SUB_REPORT panel to self-exclude from its picker.
    this.reportTemplateId = this.isAdd ? null : routeReportTemplateId;
    if (this.isAdd) {
      const seed = this.takeSeed();
      this.reportTemplate = {
        name: seed.name ?? '',
        format: seed.format ?? TbReportFormat.PDF,
        type: seed.type ?? ReportTemplateType.REPORT,
        description: seed.description
      } as ReportTemplate;
      this.applyConfig(seed.config ?? newTemplateConfig(this.reportTemplate.format));
    } else {
      this.reportService.getReportTemplateById(this.reportTemplateId).subscribe(reportTemplate => {
        this.reportTemplate = reportTemplate;
        this.applyConfig(deserialize(reportTemplate.configuration));
      });
    }
  }

  // Seeded by whoever navigated here: the templates list's "Create new report template" dialog
  // (report-template-dialog.component.ts) passes name/format/type/description through router state,
  // and its "Import report template" action passes a whole parsed config as well. Read off
  // history.state rather than Router#getCurrentNavigation() - the same idiom
  // iot-hub-items-page.component.ts uses - because getCurrentNavigation() is only non-null while a
  // navigation is in flight, and it survives a reload of the 'new' URL.
  //
  // Consumed, not just read: the seed is cleared from history.state immediately, so navigating back
  // to this route later (browser Back, or a fresh "new" without the dialog) starts blank instead of
  // silently resurrecting a template the user already abandoned.
  private takeSeed(): SeededReportTemplate {
    const seed: SeededReportTemplate = history.state?.reportTemplateSeed ?? {};
    if (history.state?.reportTemplateSeed) {
      history.replaceState({...history.state, reportTemplateSeed: undefined}, '');
    }
    return seed;
  }

  onPageSettingsChange(value: ReportPageSettings): void {
    if (this.config?.format === 'PDF') {
      Object.assign(this.config, value);
    }
    this.pageSettings = value;
  }

  // The page-settings componentSelected hop (Task 15A): a click on a component inside either
  // hosted header/footer editor bubbles page-settings -> here, same as the body canvas's own
  // (selected)="selected = $event" - the shell's single `selected` field switches the right pane to
  // that component's config panel regardless of which array it actually lives in.
  onComponentSelected(component: ReportComponent): void {
    this.selected = component;
  }

  // REQUIRED deselect affordance (Task 15A): the only way back to page-settings once a component is
  // selected - the config pane's per-type panels have no close/back control of their own, and
  // page-settings (where header/footer live) only mounts when selected === null.
  deselect(): void {
    this.selected = null;
  }

  // Merge-in-place, mirroring onPageSettingsChange above: config.components must keep the SAME
  // element reference the canvas (T6) holds, so the selected card doesn't churn and `selected`
  // stays a valid pointer into the array - replacing the array element by index would drop that
  // reference. Every per-type panel (T8's DIVIDER, T9-T11) always emits a full ReportComponent
  // (all fields + `type`), so Object.assign copies every field with nothing left stale.
  onComponentChange(value: ReportComponent): void {
    if (this.selected) {
      Object.assign(this.selected, value);
    }
  }

  // T6's canvas mutates `components` in place and re-emits the same reference for every reorder/
  // add/delete, so reassigning config.components here is a safe no-op-or-reassign in the common
  // case. Delete is the one mutation that can leave `selected` pointing at an object no longer in
  // ANY array the right panel could still be validly editing (an orphan whose edits would be
  // silently discarded on save), so clear it when that happens; reorder/add keep `selected`
  // untouched. selectionStillReachable() (below) checks every array a component can live in, not
  // just `components` - a still-selected body component is always found in ITS one true array
  // regardless of what just changed, so this is exactly as precise as the old `!components
  // .includes(this.selected)` check for the body case, and additionally correct once header/footer
  // exist (Task 15A).
  onComponentsChange(components: ReportComponent[]): void {
    this.config.components = components;
    if (!this.selectionStillReachable()) {
      this.selected = null;
    }
  }

  // Task 15A: fired by tb-report-page-settings's own componentsChanged (re-emitted from whichever
  // hosted header/footer editor's own componentsChanged - a nested tb-report-canvas add/reorder/
  // delete, at any depth). NOT routed through the page-settings CVA's (ngModelChange)
  // ="config.header = $event" channel: that fires on every enabled/firstPage toggle too, and
  // assigning config.header = $event is a same-reference no-op for a canvas mutation (the nested
  // canvas already mutated config.header.components in place) - this dedicated signal is what
  // actually triggers the recompute.
  onHeaderFooterComponentsChanged(): void {
    if (!this.selectionStillReachable()) {
      this.selected = null;
    }
  }

  // Walks every array a ReportComponent can live in - config.components (body) plus, for a PDF
  // config only (config.header/footer don't exist on the CSV variant), config.header/footer's own
  // components and one level of firstPage's components - and reports whether `selected` still lives
  // in one of them. A component object has exactly one home array at a time, so checking the full
  // set (rather than just the array that just changed) is always correct: a still-live `selected`
  // is found in its one true array regardless of which other array just mutated, and a deleted one
  // is found in none.
  private selectionStillReachable(): boolean {
    if (!this.selected) {
      return true;
    }
    const config = this.config;
    const arrays: ReportComponent[][] = [config.components];
    if (config.format === 'PDF') {
      const header = config.header;
      const footer = config.footer;
      if (header?.components) {
        arrays.push(header.components);
      }
      if (header?.firstPage?.components) {
        arrays.push(header.firstPage.components);
      }
      if (footer?.components) {
        arrays.push(footer.components);
      }
      if (footer?.firstPage?.components) {
        arrays.push(footer.firstPage.components);
      }
    }
    return arrays.some(components => components.includes(this.selected));
  }

  // The 5 C1 types plus C2 Task 11's 3 table types plus Task 13's SUB_REPORT plus Task 14's
  // DASHBOARD now have a dedicated branch in the right panel below; the remaining palette types
  // (the C2 preset/composite cluster) fall back to a placeholder rather than rendering blank.
  hasConfigPanel(type: string): boolean {
    return ['PAGE_BREAK', 'DIVIDER', 'HEADING', 'RICH_TEXT', 'IMAGE', 'ENTITY_TABLE', 'ALARM_TABLE', 'TIME_SERIES_TABLE', 'SUB_REPORT', 'DASHBOARD'].includes(type);
  }

  save(): void {
    const reportTemplate: ReportTemplate = {
      ...this.reportTemplate,
      configuration: serialize(this.config)
    };
    this.reportService.saveReportTemplate(reportTemplate).subscribe(() => {
      this.goBack();
    });
  }

  goBack(): void {
    this.router.navigate(['../'], {relativeTo: this.route});
  }

  // Toolbar Export button (C2 Task 17): downloads the LIVE, possibly-unsaved config (not the stale
  // reportTemplate.configuration) as a standalone JSON file - see report-import-export.ts.
  exportTemplate(): void {
    this.reportImportExport.exportTemplate(this.reportTemplate, this.config);
  }

  // Toolbar Import button (C2 Task 17): loads a previously-exported JSON file INTO the editor for
  // review - it deliberately never calls save(); the user still has to hit Save themselves. `file`
  // is undefined if the native file input's change event fired with no selection (e.g. the browser
  // dialog was cancelled without picking a file).
  importTemplate(file: File | undefined): void {
    if (!file) {
      return;
    }
    this.reportImportExport.importTemplate(file).subscribe({
      next: (imported) => {
        this.reportTemplate.name = imported.name;
        this.reportTemplate.format = imported.format;
        this.reportTemplate.type = imported.type;
        this.applyConfig(imported.config);
      },
      error: (err) => {
        console.error('Failed to import report template', err);
        this.store.dispatch(new ActionNotificationShow({
          message: this.translate.instant('report.designer.import-error'),
          type: 'error'
        }));
      }
    });
  }

  // Shared by ngOnInit's 2 load paths (add/existing) AND importTemplate() above, so import
  // re-derives pageSettings/aliasController exactly like a fresh load does (DRY). aliasController
  // is technically safe to leave untouched across a config replacement - createReportAliasController
  // closes over `() => this.config`, i.e. over THIS component instance, not over the config object
  // itself, so it already re-reads whatever `this.config` currently is on every call - but it's
  // recreated here anyway for explicitness/defensiveness (cheap, and removes any doubt if
  // report-alias-controller.ts's factory signature ever changes to capture the config value
  // directly instead of a getter). `selected` is cleared: after import this.config is an entirely
  // new tree, so whatever was selected before almost certainly doesn't live in it anymore - the
  // same state a fresh ngOnInit load starts from (field initializer, never non-null yet).
  private applyConfig(config: ReportTemplateConfigModel): void {
    this.config = config;
    this.pageSettings = toPageSettings(this.config);
    this.aliasController = createReportAliasController(() => this.config);
    this.selected = null;
  }
}
