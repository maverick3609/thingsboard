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

// Plain instantiation (no TestBed): ngOnInit/save() only touch the injected ActivatedRoute/Router/
// ReportService, never the DOM, so a `new`'d instance with hand-rolled fakes is enough to prove the
// load/save wiring - it also skips compiling the host template (SharedModule/HomeComponentsModule)
// that a full TestBed harness would drag in for a shell with no behavior of its own yet.
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ReportTemplateEditorComponent } from './report-template-editor.component';
import { serialize } from './report-configuration.serializer';
import { ReportService } from '@core/http/report.service';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { DividerComponent, PageOrientation, PageSize, PdfReportTemplateConfig, ReportComponent } from '@shared/models/report-configuration.models';
import { ReportImportExportService } from './report-import-export';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { MatDialog } from '@angular/material/dialog';
import {
  ReportAliasesFiltersDialogComponent
} from '@home/pages/report/report-template/designer/data/report-aliases-filters-dialog.component';

describe('ReportTemplateEditorComponent', () => {
  let reportService: jasmine.SpyObj<ReportService>;
  let router: jasmine.SpyObj<Router>;
  let reportImportExport: jasmine.SpyObj<ReportImportExportService>;
  let store: jasmine.SpyObj<{ dispatch: (action: unknown) => void }>;
  let translate: jasmine.SpyObj<{ instant: (key: string) => string }>;
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(() => {
    reportService = jasmine.createSpyObj('ReportService', ['getReportTemplateById', 'saveReportTemplate']);
    router = jasmine.createSpyObj('Router', ['navigate']);
    reportImportExport = jasmine.createSpyObj('ReportImportExportService', ['exportTemplate', 'importTemplate']);
    store = jasmine.createSpyObj('Store', ['dispatch']);
    translate = jasmine.createSpyObj('TranslateService', ['instant']);
    dialog = jasmine.createSpyObj('MatDialog', ['open']);
    dialog.open.and.returnValue({afterClosed: () => of(undefined)} as any);
  });

  function routeWithId(reportTemplateId: string): ActivatedRoute {
    return { snapshot: { paramMap: convertToParamMap({ reportTemplateId }) } } as unknown as ActivatedRoute;
  }

  // Plain-instantiation convention (see file header note above the describe block in the original
  // C1 Task 3 spec): centralizes the now-7-argument constructor call so the C2 Task 17 dependencies
  // (reportImportExport/store/translate) and C3's MatDialog aren't repeated at every call site below.
  function newComponent(route: ActivatedRoute): ReportTemplateEditorComponent {
    return new ReportTemplateEditorComponent(route, router, reportService, reportImportExport, store as any,
      translate as any, dialog);
  }

  // history.state is the seed transport (see takeSeed()) - stub it rather than driving a real
  // navigation, which plain instantiation has no router-outlet for.
  function seedHistoryState(seed: unknown): void {
    spyOnProperty(window.history, 'state', 'get').and.returnValue({reportTemplateSeed: seed});
    spyOn(window.history, 'replaceState');
  }

  it('applies the add-dialog seed and builds a CSV config when the seed says CSV', () => {
    seedHistoryState({name: 'Meter export', format: TbReportFormat.CSV, type: ReportTemplateType.SUB_REPORT});
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    expect(component.reportTemplate.name).toBe('Meter export');
    expect(component.reportTemplate.format).toBe(TbReportFormat.CSV);
    expect(component.reportTemplate.type).toBe(ReportTemplateType.SUB_REPORT);
    // A CSV config carries no page settings at all - the shell must not offer a page-settings panel
    // whose edits would have nowhere to go.
    expect(component.config.format).toBe('CSV');
    expect(component.pageSettings).toBeNull();
  });

  it('consumes the seed so a later visit to the same route starts blank', () => {
    seedHistoryState({name: 'Meter export', format: TbReportFormat.CSV, type: ReportTemplateType.REPORT});
    newComponent(routeWithId('new')).ngOnInit();

    expect(window.history.replaceState).toHaveBeenCalledWith(
      jasmine.objectContaining({reportTemplateSeed: undefined}), '');
  });

  it('starts from an empty PDF config when reportTemplateId is "new"', () => {
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    expect(component.isAdd).toBe(true);
    expect(component.config.format).toBe('PDF');
    expect(component.config.components).toEqual([]);
    expect(reportService.getReportTemplateById).not.toHaveBeenCalled();
    // null (not the literal 'new' route segment) - Task 13's SUB_REPORT panel self-exclusion has no
    // real id to exclude for a not-yet-saved template.
    expect(component.reportTemplateId).toBeNull();
  });

  it('loads and deserializes an existing template by id', () => {
    const stored: ReportTemplate = {
      name: 'Q3 report',
      format: TbReportFormat.PDF,
      type: ReportTemplateType.REPORT,
      configuration: { format: 'PDF', components: [{ type: 'HEADING', value: 'Q3' }] }
    };
    reportService.getReportTemplateById.and.returnValue(of(stored));
    const component = newComponent(routeWithId('t1'));

    component.ngOnInit();

    expect(reportService.getReportTemplateById).toHaveBeenCalledWith('t1');
    expect(component.isAdd).toBe(false);
    expect(component.reportTemplate).toBe(stored);
    expect(component.config.components).toEqual([{ type: 'HEADING', value: 'Q3' }]);
    expect(component.reportTemplateId).toBe('t1');
  });

  it('derives pageSettings from a PDF config on init, and merges an edit back into config without touching fields it does not own', () => {
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    expect(component.pageSettings.pageSize).toBe((component.config as PdfReportTemplateConfig).pageSize);

    // Sentinels for root config fields this panel does not edit - T6's canvas owns `components`,
    // later tasks own entityAliases/filters. Object.assign(this.config, value) (not this.config =
    // value) is what keeps these intact; a regression to whole-object replacement would silently
    // wipe them while the pageSize-only assertions below stayed green, so assert both.
    const componentsSentinel: ReportComponent[] = [{ type: 'DIVIDER' }];
    const entityAliasesSentinel = [{ id: 'alias-1' }];
    const filtersSentinel = [{ id: 'filter-1' }];
    component.config.components = componentsSentinel;
    component.config.entityAliases = entityAliasesSentinel;
    component.config.filters = filtersSentinel;

    component.onPageSettingsChange({ ...component.pageSettings, pageSize: PageSize.LETTER });

    expect((component.config as PdfReportTemplateConfig).pageSize).toBe(PageSize.LETTER);
    expect(component.pageSettings.pageSize).toBe(PageSize.LETTER);
    expect(component.config.components).toBe(componentsSentinel);
    expect(component.config.entityAliases).toBe(entityAliasesSentinel);
    expect(component.config.filters).toBe(filtersSentinel);
  });

  it('merges a component edit in place, preserving the config.components element and selected references', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();

    // Same object in both places, mirroring T6's canvas: `selected` is a pointer into
    // config.components, not a copy. onComponentChange must mutate that shared object
    // (Object.assign) rather than replace it, or the array element and `selected` would
    // diverge and the canvas's trackByComponent(identity) would churn the card.
    const divider: DividerComponent = { type: 'DIVIDER', color: '#000' };
    component.config.components = [divider];
    component.selected = divider;

    component.onComponentChange({ type: 'DIVIDER', color: '#fff', widthPx: 2 });

    expect(component.config.components[0]).toBe(divider);
    expect(component.selected).toBe(divider);
    expect(divider.color).toBe('#fff');
    expect(divider.widthPx).toBe(2);
  });

  it('hasConfigPanel is true for all 10 real palette component types (C1\'s 5, C2 Task 11\'s 3 tables, Task 13\'s SUB_REPORT, Task 14\'s DASHBOARD); false for the renderer-only ERROR type', () => {
    const component = newComponent(routeWithId('new'));

    expect(component.hasConfigPanel('PAGE_BREAK')).toBe(true);
    expect(component.hasConfigPanel('DIVIDER')).toBe(true);
    expect(component.hasConfigPanel('HEADING')).toBe(true);
    expect(component.hasConfigPanel('RICH_TEXT')).toBe(true);
    expect(component.hasConfigPanel('IMAGE')).toBe(true);
    expect(component.hasConfigPanel('ENTITY_TABLE')).toBe(true);
    expect(component.hasConfigPanel('ALARM_TABLE')).toBe(true);
    expect(component.hasConfigPanel('TIME_SERIES_TABLE')).toBe(true);
    expect(component.hasConfigPanel('SUB_REPORT')).toBe(true);
    expect(component.hasConfigPanel('DASHBOARD')).toBe(true);
    // ERROR (components/ErrorComponent.java) is a renderer-only fallback, never palette-draggable or
    // user-configured - the one real ReportComponentType left with no config panel.
    expect(component.hasConfigPanel('ERROR')).toBe(false);
  });

  it('creates an aliasController in ngOnInit, backed by the live config (Task 11)', () => {
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    expect(component.aliasController).toBeTruthy();
    component.config.entityAliases = [{ id: 'a1', alias: 'Devices' }];
    expect(component.aliasController.getEntityAliases().a1?.alias).toBe('Devices');
  });

  it('onComponentsChange clears selected when it was deleted from the emitted array, but leaves it untouched on reorder/add', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();

    const divider: DividerComponent = { type: 'DIVIDER', color: '#000' };
    const heading: ReportComponent = { type: 'HEADING', value: 'h' };
    component.config.components = [divider, heading];
    component.selected = divider;

    // Reorder/add: `selected` (divider) is still in the emitted array - untouched.
    const reordered = [heading, divider];
    component.onComponentsChange(reordered);
    expect(component.config.components).toBe(reordered);
    expect(component.selected).toBe(divider);

    // Delete: `selected` (divider) is no longer in the emitted array - cleared.
    const afterDelete = [heading];
    component.onComponentsChange(afterDelete);
    expect(component.config.components).toBe(afterDelete);
    expect(component.selected).toBeNull();
  });

  it('onComponentSelected sets selected - the page-settings componentSelected hop, e.g. for a header/footer component click', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    const headerHeading: ReportComponent = { type: 'HEADING', value: 'in header' };

    component.onComponentSelected(headerHeading);

    expect(component.selected).toBe(headerHeading);
  });

  it('deselect nulls selected, giving the config pane a way back to page-settings (where header/footer live)', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    component.selected = { type: 'DIVIDER' };

    component.deselect();

    expect(component.selected).toBeNull();
  });

  it('onHeaderFooterComponentsChanged nulls selected when it was a header component just removed from config.header.components', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    const headerHeading: ReportComponent = { type: 'HEADING', value: 'in header' };
    (component.config as PdfReportTemplateConfig).header = { enabled: true, components: [headerHeading] };
    component.selected = headerHeading;

    // Mirrors how tb-report-canvas deletes: splice the same array in place, then the shell's
    // dedicated hook (wired to tb-report-header-footer's componentsChanged, via page-settings)
    // re-runs the cross-array reachability check.
    (component.config as PdfReportTemplateConfig).header.components = [];
    component.onHeaderFooterComponentsChanged();

    expect(component.selected).toBeNull();
  });

  it('an unrelated body change (onComponentsChange) leaves a header-selected component untouched - cross-array reachability, not just the array that changed', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    const headerHeading: ReportComponent = { type: 'HEADING', value: 'in header' };
    (component.config as PdfReportTemplateConfig).header = { enabled: true, components: [headerHeading] };
    component.selected = headerHeading;

    // Body canvas reorders/adds - unrelated to the header array the selection actually lives in.
    component.onComponentsChange([{ type: 'DIVIDER' }]);

    expect(component.selected).toBe(headerHeading);
  });

  it('selectionStillReachable (via onHeaderFooterComponentsChanged) finds a selected component nested in footer.firstPage.components', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    const footerFirstPageDivider: ReportComponent = { type: 'DIVIDER' };
    (component.config as PdfReportTemplateConfig).footer = {
      enabled: true,
      components: [],
      firstPage: { enabled: true, components: [footerFirstPageDivider] }
    };
    component.selected = footerFirstPageDivider;

    component.onHeaderFooterComponentsChanged();

    expect(component.selected).toBe(footerFirstPageDivider);
  });

  it('save() persists the template with configuration === serialize(config) and navigates back', () => {
    reportService.saveReportTemplate.and.returnValue(of({} as ReportTemplate));
    const route = routeWithId('new');
    const component = newComponent(route);
    component.ngOnInit();
    component.reportTemplate.name = 'My report';

    component.save();

    expect(reportService.saveReportTemplate).toHaveBeenCalledTimes(1);
    const saved = reportService.saveReportTemplate.calls.mostRecent().args[0] as ReportTemplate;
    expect(saved.name).toBe('My report');
    expect(saved.configuration).toEqual(serialize(component.config));
    expect(router.navigate).toHaveBeenCalledWith(['../'], { relativeTo: route });
  });

  it('exportTemplate() delegates to reportImportExport.exportTemplate with the current reportTemplate and LIVE config', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    component.reportTemplate.name = 'My report';

    component.exportTemplate();

    expect(reportImportExport.exportTemplate).toHaveBeenCalledTimes(1);
    expect(reportImportExport.exportTemplate).toHaveBeenCalledWith(component.reportTemplate, component.config);
  });

  it('importTemplate(undefined) does nothing (e.g. the file picker was cancelled with no selection)', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();

    component.importTemplate(undefined);

    expect(reportImportExport.importTemplate).not.toHaveBeenCalled();
  });

  it('importTemplate(file) loads the imported template into the editor WITHOUT saving, re-deriving pageSettings and aliasController from the NEW config', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    // Sentinel: a component selected in the OLD config tree must not survive the import - the new
    // tree almost certainly doesn't contain this exact object (Task 17's "watch for" note).
    const oldDivider: DividerComponent = { type: 'DIVIDER', color: '#000' };
    component.config.components = [oldDivider];
    component.selected = oldDivider;
    const importedConfig: PdfReportTemplateConfig = {
      format: 'PDF',
      components: [{ type: 'HEADING', value: 'Imported' }],
      pageSize: PageSize.LETTER,
      pageOrientation: PageOrientation.LANDSCAPE,
      pageMargins: { left: 10, right: 10, top: 10, bottom: 10 },
      entityAliases: [{ id: 'a1', alias: 'Imported alias' } as any]
    };
    const file = {} as File;
    reportImportExport.importTemplate.and.returnValue(of({
      name: 'Imported report',
      format: TbReportFormat.CSV,
      type: ReportTemplateType.SUB_REPORT,
      config: importedConfig
    }));

    component.importTemplate(file);

    expect(reportImportExport.importTemplate).toHaveBeenCalledWith(file);
    expect(component.reportTemplate.name).toBe('Imported report');
    expect(component.reportTemplate.format).toBe(TbReportFormat.CSV);
    expect(component.reportTemplate.type).toBe(ReportTemplateType.SUB_REPORT);
    expect(component.config).toBe(importedConfig);
    expect(component.pageSettings.pageSize).toBe(PageSize.LETTER);
    expect(component.selected).toBeNull();
    // aliasController rebound to the NEW config, not stuck on the old one (the staleness concern
    // flagged in task-17-interfaces.md): reading it now must reflect importedConfig's aliases.
    expect(component.aliasController.getEntityAliases().a1?.alias).toBe('Imported alias');
    expect(reportService.saveReportTemplate).not.toHaveBeenCalled();
  });

  it('importTemplate(file) shows an error notification (and does not touch reportTemplate/config) when the import fails validation', () => {
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    const configBefore = component.config;
    const nameBefore = component.reportTemplate.name;
    translate.instant.and.returnValue('Failed to import report template.');
    reportImportExport.importTemplate.and.returnValue(throwError(() => new Error('Invalid report template file: missing "format"')));

    component.importTemplate({} as File);

    expect(component.config).toBe(configBefore);
    expect(component.reportTemplate.name).toBe(nameBefore);
    expect(store.dispatch).toHaveBeenCalledTimes(1);
    const action = store.dispatch.calls.mostRecent().args[0] as ActionNotificationShow;
    expect(action.notification.type).toBe('error');
    expect(action.notification.message).toBe('Failed to import report template.');
  });

  // C3 (PE toolbar parity): Save/Decline are gated on isDirty.
  it('an existing template loads clean; any edit marks it dirty', () => {
    const existing: ReportTemplate = {
      name: 'Weekly', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT,
      configuration: {format: 'PDF', components: [], pageSize: 'A4', pageOrientation: 'PORTRAIT',
        pageMargins: {left: 40, right: 40, top: 40, bottom: 40}}
    } as ReportTemplate;
    reportService.getReportTemplateById.and.returnValue(of(existing));
    const component = newComponent(routeWithId('t-1'));

    component.ngOnInit();
    expect(component.isDirty).toBe(false);

    component.markDirty();
    expect(component.isDirty).toBe(true);
  });

  // A never-persisted template is unsaved work by definition, so Save must be reachable at once.
  it('a new template starts dirty', () => {
    seedHistoryState({name: 'Fresh', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT});
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    expect(component.isDirty).toBe(true);
  });

  it('decline() restores the loaded template and config, and clears the dirty flag', () => {
    const existing: ReportTemplate = {
      name: 'Weekly', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT,
      configuration: {format: 'PDF', components: [{type: 'DIVIDER'}], pageSize: 'A4',
        pageOrientation: 'PORTRAIT', pageMargins: {left: 40, right: 40, top: 40, bottom: 40}}
    } as ReportTemplate;
    reportService.getReportTemplateById.and.returnValue(of(existing));
    const component = newComponent(routeWithId('t-1'));
    component.ngOnInit();

    component.reportTemplate.name = 'Renamed';
    component.config.components.push({type: 'PAGE_BREAK'});
    component.markDirty();
    component.decline();

    expect(component.reportTemplate.name).toBe('Weekly');
    expect(component.config.components).toEqual([{type: 'DIVIDER'}]);
    expect(component.isDirty).toBe(false);
  });

  // PE's new-template default enables both (docs/images/reporting-template-1.png shows three drop
  // zones on a brand-new template), so a fresh config already carries them.
  it('a new PDF template starts with header AND footer enabled, per PE', () => {
    seedHistoryState({name: 'Fresh', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT});
    const component = newComponent(routeWithId('new'));

    component.ngOnInit();

    const config = component.config as PdfReportTemplateConfig;
    expect(config.header).toEqual({enabled: true, components: []});
    expect(config.footer).toEqual({enabled: true, components: []});
    expect(config.namePattern).toBe('report-%d{yyyy-MM-dd_HH:mm:ss}');
    expect(config.timeDataPattern).toBe('yyyy-MM-dd HH:mm:ss');
    expect(config.pageMargins).toEqual({left: 20, right: 20, top: 20, bottom: 20});
    expect(config.pageBackground).toBe('#fff');
  });

  // A config that reaches the editor WITHOUT them - an older template, or an imported file - must
  // not gain an empty header just by being opened (C2 Task 18's byte-fidelity round-trip). The
  // header/footer editor starts on a detached object and this handler is what attaches it, on the
  // first real edit.
  it('onHeaderFooterChange attaches the edited HeaderFooter to a config that had none, and marks it dirty', () => {
    seedHistoryState({name: 'Fresh', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT});
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    delete (component.config as PdfReportTemplateConfig).header;
    delete (component.config as PdfReportTemplateConfig).footer;
    component.isDirty = false;
    const edited = {enabled: true, components: [] as ReportComponent[]};

    component.onHeaderFooterChange(true, edited);

    expect((component.config as PdfReportTemplateConfig).header).toBe(edited);
    expect((component.config as PdfReportTemplateConfig).footer).toBeUndefined();
    expect(component.isDirty).toBe(true);
  });

  it('showHeaderFooter is PDF-only and never true for a subreport', () => {
    seedHistoryState({name: 'Fresh', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT});
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    expect(component.showHeaderFooter).toBe(true);

    component.reportTemplate.type = ReportTemplateType.SUB_REPORT;
    expect(component.showHeaderFooter).toBe(false);

    component.reportTemplate.type = ReportTemplateType.REPORT;
    component.config = {format: 'CSV', components: []};
    expect(component.showHeaderFooter).toBe(false);
  });

  // Opening the dialog is not an edit; only a change to the two arrays it manages is.
  it('the aliases dialog marks the template dirty only when it actually changed something', () => {
    seedHistoryState({name: 'Fresh', format: TbReportFormat.PDF, type: ReportTemplateType.REPORT});
    const component = newComponent(routeWithId('new'));
    component.ngOnInit();
    component.isDirty = false;

    component.openAliases();

    expect(dialog.open).toHaveBeenCalledWith(ReportAliasesFiltersDialogComponent, jasmine.objectContaining({
      data: {config: component.config, mode: 'aliases'}
    }));
    expect(component.isDirty).toBe(false);

    dialog.open.and.callFake(() => {
      component.config.entityAliases = [{id: 'a-1', alias: 'Devices', filter: null}];
      return {afterClosed: () => of(undefined)} as any;
    });
    component.openFilters();

    expect(component.isDirty).toBe(true);
  });
});
