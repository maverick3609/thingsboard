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

// Plain data module - no DOM, no Angular, so this is a plain describe/it over the exported const
// (same convention as report-palette.component.spec.ts's REPORT_COMPONENT_PALETTE assertions).
// Fixture-cloning is proven directly against PE_DEFAULT_CONFIGS (Task 1) rather than duplicating the
// expected literal by hand, so a future accidental edit to either the fixture or the preset wiring
// shows up here.
import { REPORT_COMPONENT_PRESETS } from './report-component-presets';
import { PE_DEFAULT_CONFIGS } from '@home/pages/report/report-template/designer/fixtures/pe-default-configs';
import { ReportComponentType } from '@shared/models/report-configuration.models';

// Task 16's 11 RICH_TEXT composite presets, in PE palette order (confirmed against the PE jar's own
// Map insertion order: textSection/textImage/imageText, then the 5 logo variants, then the 3
// footers - see task-16-interfaces.md).
const RICH_TEXT_PRESET_KEYS = [
  'textSection', 'textImage', 'imageText',
  'logoHeading', 'headingLogo', 'logoText', 'textLogo', 'logoText2',
  'footer1', 'footer2', 'footer3'
];

describe('REPORT_COMPONENT_PRESETS', () => {

  it('lists exactly the 13 presets (2 HEADING + 11 RICH_TEXT composites) with a preview + labelKey', () => {
    expect(REPORT_COMPONENT_PRESETS.map(p => p.key)).toEqual(['pageNumber', 'createdTime', ...RICH_TEXT_PRESET_KEYS]);
    REPORT_COMPONENT_PRESETS.forEach(preset => {
      expect(preset.preview).toMatch(/^\/assets\/report\/components\/[a-z0-9-]+\.svg$/);
      expect(preset.labelKey).toBeTruthy();
    });
  });

  it('the 2 HEADING presets keep type HEADING; the 11 composite presets are type RICH_TEXT', () => {
    ['pageNumber', 'createdTime'].forEach(key => {
      expect(REPORT_COMPONENT_PRESETS.find(p => p.key === key).type).toBe(ReportComponentType.HEADING);
    });
    RICH_TEXT_PRESET_KEYS.forEach(key => {
      expect(REPORT_COMPONENT_PRESETS.find(p => p.key === key).type).toBe(ReportComponentType.RICH_TEXT);
    });
  });

  it("pageNumber's factory() clones PE_DEFAULT_CONFIGS.pageNumber verbatim (value 'Page: ${pageNumber}/${totalPages}')", () => {
    const preset = REPORT_COMPONENT_PRESETS.find(p => p.key === 'pageNumber');

    const cloned: any = preset.factory();

    expect(cloned).toEqual(PE_DEFAULT_CONFIGS.pageNumber);
    expect(cloned.value).toBe('Page: ${pageNumber}/${totalPages}');
  });

  it("createdTime's factory() clones PE_DEFAULT_CONFIGS.createdTime verbatim (value 'Created: ${reportCreatedTime}')", () => {
    const preset = REPORT_COMPONENT_PRESETS.find(p => p.key === 'createdTime');

    const cloned: any = preset.factory();

    expect(cloned).toEqual(PE_DEFAULT_CONFIGS.createdTime);
    expect(cloned.value).toBe('Created: ${reportCreatedTime}');
  });

  it("footer1's factory() clones PE_DEFAULT_CONFIGS.footer1 verbatim", () => {
    const preset = REPORT_COMPONENT_PRESETS.find(p => p.key === 'footer1');

    const cloned: any = preset.factory();

    expect(cloned).toEqual(PE_DEFAULT_CONFIGS.footer1);
    expect(cloned.value).toBe(PE_DEFAULT_CONFIGS.footer1.value);
  });

  it("logoHeading's factory() keeps PE's logo-placeholder tb-image ref VERBATIM (the user swaps it via the image picker at authoring time - not repointed here)", () => {
    const preset = REPORT_COMPONENT_PRESETS.find(p => p.key === 'logoHeading');

    const cloned: any = preset.factory();

    expect(cloned.value).toContain('tb-image;/assets/report/components/logo-placeholder.svg');
  });

  it('returns a fresh, distinct object from every factory() call - never a shared reference', () => {
    REPORT_COMPONENT_PRESETS.forEach(preset => {
      const first: any = preset.factory();
      const second: any = preset.factory();

      expect(first).toEqual(second);
      expect(first).not.toBe(second);

      // Mutating one clone (as an in-designer edit of a dropped card would) must never be visible
      // through a later factory() call for the same preset.
      first.value = 'mutated';
      expect((preset.factory() as any).value).not.toBe('mutated');
    });
  });
});
