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
import { of } from 'rxjs';
import { ReportTemplateEditorComponent } from './report-template-editor.component';
import { serialize } from './report-configuration.serializer';
import { ReportService } from '@core/http/report.service';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { DividerComponent, PageSize, PdfReportTemplateConfig, ReportComponent } from '@shared/models/report-configuration.models';

describe('ReportTemplateEditorComponent', () => {
  let reportService: jasmine.SpyObj<ReportService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    reportService = jasmine.createSpyObj('ReportService', ['getReportTemplateById', 'saveReportTemplate']);
    router = jasmine.createSpyObj('Router', ['navigate']);
  });

  function routeWithId(reportTemplateId: string): ActivatedRoute {
    return { snapshot: { paramMap: convertToParamMap({ reportTemplateId }) } } as unknown as ActivatedRoute;
  }

  it('starts from an empty PDF config when reportTemplateId is "new"', () => {
    const component = new ReportTemplateEditorComponent(routeWithId('new'), router, reportService);

    component.ngOnInit();

    expect(component.isAdd).toBe(true);
    expect(component.config.format).toBe('PDF');
    expect(component.config.components).toEqual([]);
    expect(reportService.getReportTemplateById).not.toHaveBeenCalled();
  });

  it('loads and deserializes an existing template by id', () => {
    const stored: ReportTemplate = {
      name: 'Q3 report',
      format: TbReportFormat.PDF,
      type: ReportTemplateType.REPORT,
      configuration: { format: 'PDF', components: [{ type: 'HEADING', value: 'Q3' }] }
    };
    reportService.getReportTemplateById.and.returnValue(of(stored));
    const component = new ReportTemplateEditorComponent(routeWithId('t1'), router, reportService);

    component.ngOnInit();

    expect(reportService.getReportTemplateById).toHaveBeenCalledWith('t1');
    expect(component.isAdd).toBe(false);
    expect(component.reportTemplate).toBe(stored);
    expect(component.config.components).toEqual([{ type: 'HEADING', value: 'Q3' }]);
  });

  it('derives pageSettings from a PDF config on init, and merges an edit back into config without touching fields it does not own', () => {
    const component = new ReportTemplateEditorComponent(routeWithId('new'), router, reportService);

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
    const component = new ReportTemplateEditorComponent(routeWithId('new'), router, reportService);
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

  it('save() persists the template with configuration === serialize(config) and navigates back', () => {
    reportService.saveReportTemplate.and.returnValue(of({} as ReportTemplate));
    const route = routeWithId('new');
    const component = new ReportTemplateEditorComponent(route, router, reportService);
    component.ngOnInit();
    component.reportTemplate.name = 'My report';

    component.save();

    expect(reportService.saveReportTemplate).toHaveBeenCalledTimes(1);
    const saved = reportService.saveReportTemplate.calls.mostRecent().args[0] as ReportTemplate;
    expect(saved.name).toBe('My report');
    expect(saved.configuration).toEqual(serialize(component.config));
    expect(router.navigate).toHaveBeenCalledWith(['../'], { relativeTo: route });
  });
});
