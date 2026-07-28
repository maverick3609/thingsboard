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

// The 2 HEADING presets (Task 15B) followed by the 11 RICH_TEXT composite presets (Task 16), in PE's
// own palette order (confirmed against the PE jar's Map insertion order). report-palette.component.ts
// /report-canvas.component.ts are written preset-count-agnostic, so appending here needed no other
// change.
//
// factory() deep-clones the PE fixture on every call (never returns a shared reference) so that two
// separate drops of the same preset - or a canvas.onDrop() insertion followed by an in-designer edit
// of one dropped card - can never let one instance's edits leak into another's.
//
// The 7 logo-variant RICH_TEXT presets (logoHeading/headingLogo/logoText/textLogo/logoText2/footer2/
// footer3) embed <img src="tb-image;/assets/report/components/logo-placeholder.svg"> in their cloned
// `value`. This is PE's own shipped PLACEHOLDER, not a real logo - the user swaps it for their own
// image via the platform's image picker after dropping the preset. Kept verbatim on purpose; do NOT
// repoint it to any bundled Inferrix logo asset here.
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
  },
  {
    key: 'textSection',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/text-section.svg',
    labelKey: 'report.designer.preset-text-section',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.textSection)
  },
  {
    key: 'textImage',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/text-image.svg',
    labelKey: 'report.designer.preset-text-image',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.textImage)
  },
  {
    key: 'imageText',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/image-text.svg',
    labelKey: 'report.designer.preset-image-text',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.imageText)
  },
  {
    key: 'logoHeading',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/logo-heading.svg',
    labelKey: 'report.designer.preset-logo-heading',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.logoHeading)
  },
  {
    key: 'headingLogo',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/heading-logo.svg',
    labelKey: 'report.designer.preset-heading-logo',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.headingLogo)
  },
  {
    key: 'logoText',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/logo-text.svg',
    labelKey: 'report.designer.preset-logo-text',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.logoText)
  },
  {
    key: 'textLogo',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/text-logo.svg',
    labelKey: 'report.designer.preset-text-logo',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.textLogo)
  },
  {
    key: 'logoText2',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/logo-text-2.svg',
    labelKey: 'report.designer.preset-logo-text-2',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.logoText2)
  },
  {
    key: 'footer1',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/footer-1.svg',
    labelKey: 'report.designer.preset-footer-1',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.footer1)
  },
  {
    key: 'footer2',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/footer-2.svg',
    labelKey: 'report.designer.preset-footer-2',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.footer2)
  },
  {
    key: 'footer3',
    type: ReportComponentType.RICH_TEXT,
    preview: '/assets/report/components/footer-3.svg',
    labelKey: 'report.designer.preset-footer-3',
    factory: () => deepClone(PE_DEFAULT_CONFIGS.footer3)
  }
];
