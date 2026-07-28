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

import { Component } from '@angular/core';
import { ReportComponentType } from '@shared/models/report-configuration.models';
import { REPORT_COMPONENT_PRESETS } from '@home/pages/report/report-template/designer/presets/report-component-presets';

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

// Looked up by report-canvas.component.ts (T6) to label/icon each card by its component's `type`
// (a string-literal type, not the enum - see report-configuration.models.ts's own note on why -
// hence the plain `string` parameter); later component-config panels may reuse it too instead of
// re-declaring the same 10-entry map.
export function reportComponentTypeInfo(type: string): ReportComponentTypeInfo | undefined {
  return REPORT_COMPONENT_INFO_BY_TYPE.get(type as ReportComponentType);
}

// Draggable component library - left pane of the R2c designer (design spec
// 2026-07-24-reporting-r2c-design.md §1/§4). Purely presentational: a cdkDropList of cdkDrag items
// carrying a ReportComponentType as drag data. report-canvas.component.ts (T6) is the only drop
// target, connected via the shell's cdkDropListGroup (report-template-editor.component.html) rather
// than an explicit cdkDropListConnectedTo id, so this component never references the canvas
// directly. Dropping a palette item back onto the palette itself is a no-op (no
// (cdkDropListDropped) handler bound here); noEnterPredicate additionally blocks canvas cards from
// being dragged back into the palette, so this list only ever acts as a drag source.
@Component({
  selector: 'tb-report-palette',
  templateUrl: './report-palette.component.html',
  styleUrls: ['./report-palette.component.scss'],
  standalone: false
})
export class ReportPaletteComponent {

  readonly paletteItems = REPORT_COMPONENT_PALETTE;

  // C2 Task 15B: a second, source-only cdkDropList below the component-type list, offering pre-filled
  // starting components (PE's defaultConfig fixtures) instead of a bare skeleton. Presented separately
  // from paletteItems (own "Presets" section/title) so the two remain visually and structurally
  // distinct even though both feed the same canvas drop target. Task 16 appends 11 more entries to
  // REPORT_COMPONENT_PRESETS itself - nothing here needs to change for that.
  readonly presetItems = REPORT_COMPONENT_PRESETS;

  readonly noEnterPredicate = (): boolean => false;

}
