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
// (report-canvas.component.spec.ts, report-template-editor.component.spec.ts): REPORT_COMPONENT_
// PALETTE/reportComponentTypeInfo() are plain data/functions with no DOM, and the C2 Task 12
// drop-verify DoD below only needs the shell's hasConfigPanel() method, not its compiled template.
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { REPORT_COMPONENT_PALETTE, reportComponentTypeInfo, ReportPaletteComponent } from './report-palette.component';
import { ReportTemplateEditorComponent } from './report-template-editor.component';
import { ReportService } from '@core/http/report.service';
import { newReportComponent, ReportComponentType } from '@shared/models/report-configuration.models';
import { REPORT_COMPONENT_PRESETS } from './presets/report-component-presets';

describe('ReportPaletteComponent (data)', () => {

  const ALL_10_TYPES: ReportComponentType[] = [
    ReportComponentType.HEADING,
    ReportComponentType.RICH_TEXT,
    ReportComponentType.DIVIDER,
    ReportComponentType.PAGE_BREAK,
    ReportComponentType.IMAGE,
    ReportComponentType.ENTITY_TABLE,
    ReportComponentType.ALARM_TABLE,
    ReportComponentType.TIME_SERIES_TABLE,
    ReportComponentType.SUB_REPORT,
    ReportComponentType.DASHBOARD
  ];

  // The 5 C2 data-component types whose config panels landed in Tasks 11/13/14 - Task 12's
  // drop-verify DoD (interfaces.md "Drop-verify"): dragging one of these onto the canvas must open
  // its real config panel, not report-template-editor.component.html's placeholder branch.
  const DATA_TYPES: ReportComponentType[] = [
    ReportComponentType.ENTITY_TABLE,
    ReportComponentType.ALARM_TABLE,
    ReportComponentType.TIME_SERIES_TABLE,
    ReportComponentType.SUB_REPORT,
    ReportComponentType.DASHBOARD
  ];

  it('lists all 10 real component types, each with a non-empty PE preview thumbnail path', () => {
    expect(REPORT_COMPONENT_PALETTE.length).toBe(10);
    expect(REPORT_COMPONENT_PALETTE.map(i => i.type).slice().sort()).toEqual(ALL_10_TYPES.slice().sort());
    REPORT_COMPONENT_PALETTE.forEach(item => {
      expect(item.preview).toBeTruthy();
      expect(item.preview).toMatch(/^\/assets\/report\/components\/[a-z-]+\.svg$/);
    });
  });

  it('reportComponentTypeInfo(type) still resolves the same entry (icon + preview) for every real type', () => {
    ALL_10_TYPES.forEach(type => {
      const info = reportComponentTypeInfo(type);
      expect(info).toBeTruthy();
      expect(info.type).toBe(type);
      expect(info.icon).toBeTruthy();
      expect(info.preview).toBeTruthy();
    });
  });

  describe('presetItems (C2 Task 15B/16)', () => {
    it('lists all 13 presets (2 HEADING + 11 RICH_TEXT composites), each with a non-empty PE preview thumbnail path', () => {
      const palette = new ReportPaletteComponent();

      expect(palette.presetItems).toBe(REPORT_COMPONENT_PRESETS);
      expect(palette.presetItems.map(p => p.key)).toEqual([
        'pageNumber', 'createdTime',
        'textSection', 'textImage', 'imageText',
        'logoHeading', 'headingLogo', 'logoText', 'textLogo', 'logoText2',
        'footer1', 'footer2', 'footer3'
      ]);
      palette.presetItems.forEach(item => {
        expect(item.preview).toBeTruthy();
        expect(item.preview).toMatch(/^\/assets\/report\/components\/[a-z0-9-]+\.svg$/);
      });
    });
  });

  describe('drop-verify: palette -> canvas -> config-panel wiring for the 5 C2 data types', () => {
    let reportService: jasmine.SpyObj<ReportService>;
    let router: jasmine.SpyObj<Router>;
    let route: ActivatedRoute;

    beforeEach(() => {
      reportService = jasmine.createSpyObj('ReportService', ['getReportTemplateById', 'saveReportTemplate']);
      router = jasmine.createSpyObj('Router', ['navigate']);
      route = { snapshot: { paramMap: convertToParamMap({ reportTemplateId: 'new' }) } } as unknown as ActivatedRoute;
    });

    DATA_TYPES.forEach(type => {
      it(`${type}: palette item has a preview, newReportComponent() returns type ${type}, and the shell's hasConfigPanel() is true`, () => {
        const paletteItem = REPORT_COMPONENT_PALETTE.find(i => i.type === type);
        expect(paletteItem).toBeTruthy();
        expect(paletteItem.preview).toBeTruthy();

        // Simulates report-canvas.component.ts's onDrop() cross-container branch: a drop whose drag
        // data is this palette item's `type` expands via newReportComponent(...) into a card of the
        // same type.
        const dropped = newReportComponent(type);
        expect(dropped.type).toBe(type);

        // Simulates the shell selecting that dropped card: hasConfigPanel(type) must gate a real
        // panel (Tasks 11/13/14), not the placeholder branch.
        const shell = new ReportTemplateEditorComponent(route, router, reportService);
        expect(shell.hasConfigPanel(dropped.type)).toBe(true);
      });
    });
  });
});
