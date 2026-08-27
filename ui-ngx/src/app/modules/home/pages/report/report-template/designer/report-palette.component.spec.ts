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
// PALETTE/reportComponentTypeInfo() are plain data/functions with no DOM, and the palette component
// itself only needs a stub TranslateService (its search filters on the translated label) plus its
// lifecycle hooks called by hand - no compiled template. fakeTranslate() below returns the i18n KEY
// as the "label"; the palette's keys embed the component name (component-heading, preset-page-number,
// ...), so a substring search over the key is a faithful stand-in for searching the English label.
import { fakeAsync, tick } from '@angular/core/testing';
import { SimpleChange, SimpleChanges } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import {
  REPORT_COMPONENT_LIBRARY,
  REPORT_COMPONENT_PALETTE,
  reportComponentTypeInfo,
  ReportPaletteComponent
} from './report-palette.component';
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

  // PE's J2 - the only components a CSV report can render (report-palette.component.ts's
  // CSV_COMPONENT_TYPES). Listed here in REPORT_COMPONENT_PALETTE order, which is the order the
  // format filter preserves (it filters the palette array in place, not the set).
  // PE library order, which puts Time series table before Alarm table.
  const CSV_TYPES_IN_PALETTE_ORDER: ReportComponentType[] = [
    ReportComponentType.ENTITY_TABLE,
    ReportComponentType.TIME_SERIES_TABLE,
    ReportComponentType.ALARM_TABLE,
    ReportComponentType.SUB_REPORT
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

  const fakeTranslate = (): TranslateService =>
    ({ instant: (key: string) => key } as unknown as TranslateService);

  // Builds a palette wired like the editor does: sets @Input() format, runs ngOnInit (search
  // subscription) then ngOnChanges (format filter), so filteredItems reflects the
  // requested format with an empty search - exactly the first-render state.
  const newPalette = (format?: string): ReportPaletteComponent => {
    const palette = new ReportPaletteComponent(fakeTranslate());
    palette.format = format;
    palette.ngOnInit();
    const changes: SimpleChanges = { format: new SimpleChange(undefined, format, true) };
    palette.ngOnChanges(changes);
    return palette;
  };

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

  describe('PE library order (one flat list, no groups)', () => {
    // PE's palette is a single ordered Map of 23 entries with presets interleaved among the plain
    // components and Divider/Page break LAST - verified against the PE 4.2.0 bundle and against
    // docs/images/reporting-template-1..7.png, which scroll the library end to end.
    const PE_ORDER = [
      'report.designer.component-heading',
      'report.designer.component-rich-text',
      'report.designer.preset-text-section',
      'report.designer.preset-text-image',
      'report.designer.preset-image-text',
      'report.designer.component-entity-table',
      'report.designer.component-time-series-table',
      'report.designer.component-alarm-table',
      'report.designer.component-image',
      'report.designer.component-dashboard',
      'report.designer.component-sub-report',
      'report.designer.preset-logo-heading',
      'report.designer.preset-heading-logo',
      'report.designer.preset-logo-text',
      'report.designer.preset-text-logo',
      'report.designer.preset-logo-text-2',
      'report.designer.preset-footer-1',
      'report.designer.preset-footer-2',
      'report.designer.preset-footer-3',
      'report.designer.preset-page-number',
      'report.designer.preset-created-time',
      'report.designer.component-divider',
      'report.designer.component-page-break'
    ];

    it('is 23 entries in PE order, each with a PE preview thumbnail', () => {
      expect(REPORT_COMPONENT_LIBRARY.length).toBe(23);
      expect(REPORT_COMPONENT_LIBRARY.map(i => i.labelKey)).toEqual(PE_ORDER);
      REPORT_COMPONENT_LIBRARY.forEach(item => {
        expect(item.preview).toMatch(/^\/assets\/report\/components\/[a-z0-9-]+\.svg$/);
      });
    });

    // The two drag-data shapes report-canvas.component.ts's onDrop branches on.
    it('carries a bare type string for a component and a { preset } descriptor for a preset', () => {
      const heading = REPORT_COMPONENT_LIBRARY.find(i => i.labelKey === 'report.designer.component-heading');
      const footer1 = REPORT_COMPONENT_LIBRARY.find(i => i.labelKey === 'report.designer.preset-footer-1');

      expect(heading.preset).toBe(false);
      expect(heading.dragData).toBe(ReportComponentType.HEADING);
      expect(footer1.preset).toBe(true);
      expect((footer1.dragData as {preset: {key: string}}).preset.key).toBe('footer1');
    });

    it('covers all 10 component types and all 13 presets exactly once', () => {
      expect(REPORT_COMPONENT_LIBRARY.filter(i => !i.preset).length).toBe(10);
      expect(REPORT_COMPONENT_LIBRARY.filter(i => i.preset).length).toBe(REPORT_COMPONENT_PRESETS.length);
      expect(new Set(REPORT_COMPONENT_LIBRARY.map(i => i.labelKey)).size).toBe(23);
    });
  });

  describe('format-aware library (PE tb-report-component-library filter)', () => {
    it('unset format shows the whole 23-entry library (default = PDF/everything)', () => {
      const palette = newPalette(undefined);
      expect(palette.filteredItems).toEqual(REPORT_COMPONENT_LIBRARY);
      expect(palette.hasResults).toBe(true);
    });

    it('PDF shows the whole 23-entry library', () => {
      const palette = newPalette('PDF');
      expect(palette.filteredItems.length).toBe(23);
    });

    it('CSV shows only the 4 CSV-renderable components (library order) and hides every preset', () => {
      const palette = newPalette('CSV');
      expect(palette.filteredItems.map(i => i.type)).toEqual(CSV_TYPES_IN_PALETTE_ORDER);
      expect(palette.filteredItems.every(i => !i.preset)).toBe(true);
      expect(palette.hasResults).toBe(true);
    });
  });

  describe('debounced search (150ms)', () => {
    it('filters components by translated label, case-insensitively', fakeAsync(() => {
      const palette = newPalette('PDF');
      palette.searchControl.setValue('HEADING');
      tick(150);
      // Only the component labels containing "heading" survive (component-heading + preset-*heading*).
      expect(palette.filteredItems.map(i => i.labelKey))
        .toEqual(['report.designer.component-heading', 'report.designer.preset-logo-heading',
                  'report.designer.preset-heading-logo']);
    }));

    it('filters presets as well as components', fakeAsync(() => {
      const palette = newPalette('PDF');
      palette.searchControl.setValue('footer');
      tick(150);
      expect(palette.filteredItems.map(i => i.labelKey))
        .toEqual(['report.designer.preset-footer-1', 'report.designer.preset-footer-2',
                  'report.designer.preset-footer-3']);
      expect(palette.hasResults).toBe(true);
    }));

    it('a term matching nothing empties both groups and flips hasResults to false', fakeAsync(() => {
      const palette = newPalette('PDF');
      palette.searchControl.setValue('zzz-no-such-component');
      tick(150);
      expect(palette.filteredItems).toEqual([]);
      expect(palette.hasResults).toBe(false);
    }));

    it('search stays constrained to the CSV set for a CSV template', fakeAsync(() => {
      const palette = newPalette('CSV');
      // "heading" matches a PDF-only component; under CSV it must resolve to nothing, never re-add it.
      palette.searchControl.setValue('heading');
      tick(150);
      expect(palette.filteredItems).toEqual([]);
      // "table" hits three of the four CSV components, in PE library order.
      palette.searchControl.setValue('table');
      tick(150);
      expect(palette.filteredItems.map(i => i.type)).toEqual([
        ReportComponentType.ENTITY_TABLE,
        ReportComponentType.TIME_SERIES_TABLE,
        ReportComponentType.ALARM_TABLE
      ]);
    }));

    it('clearSearch() resets the control and restores the full lists', fakeAsync(() => {
      const palette = newPalette('PDF');
      palette.searchControl.setValue('heading');
      tick(150);
      expect(palette.filteredItems.length).toBe(3);

      palette.clearSearch();
      tick(150);
      expect(palette.searchControl.value).toBe('');
      expect(palette.filteredItems.length).toBe(23);
    }));
  });

  describe('drag-source contract (unchanged): the filtered list still carries the canvas drag data', () => {
    it('every visible item exposes one of the two [cdkDragData] shapes the canvas branches on', () => {
      const palette = newPalette('PDF');
      palette.filteredItems.forEach(item => {
        expect(ALL_10_TYPES).toContain(item.type);
        if (item.preset) {
          const preset = (item.dragData as {preset: typeof REPORT_COMPONENT_PRESETS[0]}).preset;
          expect(REPORT_COMPONENT_PRESETS).toContain(preset);
          expect(typeof preset.factory).toBe('function');
        } else {
          expect(item.dragData).toBe(item.type);
        }
      });
    });

    it('noEnterPredicate always returns false so canvas cards can never re-enter the palette', () => {
      const palette = newPalette('PDF');
      expect(palette.noEnterPredicate()).toBe(false);
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
        // panel (Tasks 11/13/14), not the placeholder branch. hasConfigPanel() is a pure, stateless
        // check that never touches reportImportExport/store/translate (C2 Task 17), so those 3
        // constructor params are left undefined here rather than mocked.
        const shell = new ReportTemplateEditorComponent(route, router, reportService, undefined, undefined, undefined, undefined);
        expect(shell.hasConfigPanel(dropped.type)).toBe(true);
      });
    });
  });
});
