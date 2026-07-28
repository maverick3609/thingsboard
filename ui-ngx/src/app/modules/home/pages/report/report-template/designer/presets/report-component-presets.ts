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

import { deepClone } from '@core/utils';
import { ReportComponent, ReportComponentType } from '@shared/models/report-configuration.models';
import { PE_DEFAULT_CONFIGS } from '@home/pages/report/report-template/designer/fixtures/pe-default-configs';

// A palette "preset" is a pre-filled starting component (PE's defaultConfig literals, Task 1's
// PE_DEFAULT_CONFIGS fixtures) rather than the bare newReportComponent(type) skeleton a plain
// palette TYPE item expands to (report-configuration.models.ts). `type` is descriptive metadata only
// - report-canvas.component.ts's onDrop() never reads it; the dropped component's own `type` field
// (set by factory()'s cloned literal) is what the shell's per-type right-panel switch keys off, same
// as any other canvas card. Once dropped, a preset-seeded component is an ordinary HEADING/RICH_TEXT
// component, indistinguishable from one authored by hand.
export interface ReportComponentPreset {
  key: string;
  type: ReportComponentType;
  preview: string;
  labelKey: string;
  factory: () => ReportComponent;
}

// The 2 HEADING presets (Task 15B). Task 16 APPENDS the 11 RICH_TEXT composite presets to this same
// array - report-palette.component.ts/report-canvas.component.ts are written preset-count-agnostic
// so that later addition needs no further change here.
//
// factory() deep-clones the PE fixture on every call (never returns a shared reference) so that two
// separate drops of the same preset - or a canvas.onDrop() insertion followed by an in-designer edit
// of one dropped card - can never let one instance's edits leak into another's.
export const REPORT_COMPONENT_PRESETS: ReportComponentPreset[] = [
  {
    key: 'pageNumber',
    type: ReportComponentType.HEADING,
    preview: '/assets/report/components/page-number.svg',
    labelKey: 'report.designer.preset-page-number',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.pageNumber)
  },
  {
    key: 'createdTime',
    type: ReportComponentType.HEADING,
    preview: '/assets/report/components/created-time.svg',
    labelKey: 'report.designer.preset-created-time',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.createdTime)
  }
];
