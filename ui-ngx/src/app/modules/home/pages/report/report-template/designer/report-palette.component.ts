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

import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges } from '@angular/core';
import { FormControl } from '@angular/forms';
import { TranslateService } from '@ngx-translate/core';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { ReportComponentType } from '@shared/models/report-configuration.models';
import {
  REPORT_COMPONENT_PRESETS,
  ReportComponentPreset
} from '@home/pages/report/report-template/designer/presets/report-component-presets';

export interface ReportComponentTypeInfo {
  type: ReportComponentType;
  icon: string;
  labelKey: string;
  // PE preview thumbnail (Task 12), served from src/assets/report/components/**; kept alongside
  // `icon` as a fallback (e.g. for any future non-image render path) rather than replacing it.
  preview: string;
}

// The 10 user-authorable component types the C1 palette offers - every ReportComponentType member
// except ERROR, which report-configuration.models.ts documents (mirroring components/ErrorComponent
// .java) as transient render-failure state with no JSON wire form; it never reaches the designer via
// the palette or otherwise.
export const REPORT_COMPONENT_PALETTE: ReportComponentTypeInfo[] = [
  { type: ReportComponentType.HEADING, icon: 'title', labelKey: 'report.designer.component-heading', preview: '/assets/report/components/heading.svg' },
  { type: ReportComponentType.RICH_TEXT, icon: 'notes', labelKey: 'report.designer.component-rich-text', preview: '/assets/report/components/rich-text.svg' },
  { type: ReportComponentType.DIVIDER, icon: 'horizontal_rule', labelKey: 'report.designer.component-divider', preview: '/assets/report/components/divider.svg' },
  { type: ReportComponentType.PAGE_BREAK, icon: 'insert_page_break', labelKey: 'report.designer.component-page-break', preview: '/assets/report/components/page-break.svg' },
  { type: ReportComponentType.IMAGE, icon: 'image', labelKey: 'report.designer.component-image', preview: '/assets/report/components/image.svg' },
  { type: ReportComponentType.ENTITY_TABLE, icon: 'table_chart', labelKey: 'report.designer.component-entity-table', preview: '/assets/report/components/entity-table.svg' },
  { type: ReportComponentType.ALARM_TABLE, icon: 'warning', labelKey: 'report.designer.component-alarm-table', preview: '/assets/report/components/alarm-table.svg' },
  { type: ReportComponentType.TIME_SERIES_TABLE, icon: 'show_chart', labelKey: 'report.designer.component-time-series-table', preview: '/assets/report/components/timeseries-table.svg' },
  { type: ReportComponentType.SUB_REPORT, icon: 'description', labelKey: 'report.designer.component-sub-report', preview: '/assets/report/components/subreport.svg' },
  { type: ReportComponentType.DASHBOARD, icon: 'dashboard', labelKey: 'report.designer.component-dashboard', preview: '/assets/report/components/dashboard.svg' }
];

const REPORT_COMPONENT_INFO_BY_TYPE = new Map<ReportComponentType, ReportComponentTypeInfo>(
  REPORT_COMPONENT_PALETTE.map(info => [info.type, info])
);

// PE's format filter (chunk-UILY2NXB.js, tb-report-component-library): a CSV report is a flat
// spreadsheet, so only these four table/sub-report components render to it. The six layout/graphic
// components (HEADING, RICH_TEXT, DIVIDER, PAGE_BREAK, IMAGE, DASHBOARD) - and every RICH_TEXT/HEADING
// preset - are PDF-only, so the palette hides them (and the whole Presets group) for a CSV template.
const CSV_COMPONENT_TYPES: ReadonlySet<ReportComponentType> = new Set([
  ReportComponentType.ENTITY_TABLE,
  ReportComponentType.TIME_SERIES_TABLE,
  ReportComponentType.ALARM_TABLE,
  ReportComponentType.SUB_REPORT
]);

// Looked up by report-canvas.component.ts (T6) to label/icon each card by its component's `type`
// (a string-literal type, not the enum - see report-configuration.models.ts's own note on why -
// hence the plain `string` parameter); later component-config panels may reuse it too instead of
// re-declaring the same 10-entry map.
export function reportComponentTypeInfo(type: string): ReportComponentTypeInfo | undefined {
  return REPORT_COMPONENT_INFO_BY_TYPE.get(type as ReportComponentType);
}

// Draggable component library - left pane of the R2c designer (design spec
// 2026-07-24-reporting-r2c-design.md §1/§4), rebuilt to PE's tb-report-component-library: a debounced
// search box over titled groups (Components / Presets) of preview TILES, format-aware (CSV shows only
// the four CSV-renderable components and hides the Presets group). Purely presentational: a cdkDropList
// of cdkDrag items carrying a ReportComponentType (or a { preset } descriptor) as drag data.
// report-canvas.component.ts (T6) is the only drop target, connected via the shell's cdkDropListGroup
// (report-template-editor.component.html) rather than an explicit cdkDropListConnectedTo id, so this
// component never references the canvas directly. Dropping a palette item back onto the palette itself
// is a no-op (no (cdkDropListDropped) handler bound here); noEnterPredicate additionally blocks canvas
// cards from being dragged back into the palette, so this list only ever acts as a drag source.
@Component({
  selector: 'tb-report-palette',
  templateUrl: './report-palette.component.html',
  styleUrls: ['./report-palette.component.scss'],
  standalone: false
})
export class ReportPaletteComponent implements OnInit, OnChanges, OnDestroy {

  // Bound from the editor as [format]="config?.format". report-configuration.models.ts keeps `format`
  // as a 'PDF' | 'CSV' string literal (not an enum), so a plain string Input matches. Undefined until
  // the editor's config resolves; treated as "show everything" (PDF) while unset.
  @Input() format: string;

  readonly searchControl = new FormControl('', { nonNullable: true });

  // Recomputed by updateLists() from `format` + the debounced search term. Initialised to the full
  // sets so the first render (before ngOnChanges/valueChanges fire) already shows every component.
  filteredComponents: ReportComponentTypeInfo[] = REPORT_COMPONENT_PALETTE;
  filteredPresets: ReportComponentPreset[] = REPORT_COMPONENT_PRESETS;

  readonly noEnterPredicate = (): boolean => false;

  private readonly destroy$ = new Subject<void>();

  constructor(private readonly translate: TranslateService) {}

  ngOnInit(): void {
    // PE debounces the search 150ms; distinctUntilChanged on the trimmed value avoids a redundant
    // recompute when only surrounding whitespace changes (same idiom as rulechain-page.component.ts's
    // ruleNodeTypeSearch). valueChanges does not emit on subscribe, so the initial list state comes
    // from the field initialisers + the ngOnChanges(format) below, not from here.
    this.searchControl.valueChanges.pipe(
      debounceTime(150),
      distinctUntilChanged((a: string, b: string) => a.trim() === b.trim()),
      takeUntil(this.destroy$)
    ).subscribe(() => this.updateLists());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.format) {
      this.updateLists();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  clearSearch(): void {
    this.searchControl.reset();
  }

  get hasResults(): boolean {
    return this.filteredComponents.length > 0 || this.filteredPresets.length > 0;
  }

  private updateLists(): void {
    const csv = this.format === 'CSV';
    const components = csv
      ? REPORT_COMPONENT_PALETTE.filter(item => CSV_COMPONENT_TYPES.has(item.type))
      : REPORT_COMPONENT_PALETTE;
    const presets = csv ? [] : REPORT_COMPONENT_PRESETS;
    const term = this.searchControl.value.trim().toLowerCase();
    this.filteredComponents = term ? components.filter(item => this.matchesSearch(item.labelKey, term)) : components;
    this.filteredPresets = term ? presets.filter(item => this.matchesSearch(item.labelKey, term)) : presets;
  }

  // Case-insensitive match on the TRANSLATED label (PE filters by visible label text, not by key).
  private matchesSearch(labelKey: string, term: string): boolean {
    return this.translate.instant(labelKey).toLowerCase().includes(term);
  }

}
