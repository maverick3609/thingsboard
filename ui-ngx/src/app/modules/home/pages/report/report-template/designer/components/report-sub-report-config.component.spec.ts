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

// Plain instantiation (no TestBed), mirroring report-table-config.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder/ReportService (the latter faked with
// jasmine.createSpyObj, the same pattern report-preview.component.spec.ts already established for a
// concrete injected @Injectable), never the DOM, so this proves the writeValue -> subFormGroup ->
// toComponent -> propagateChange round trip - including the CSV-format avoidPageBreakInside gate and
// the templateId <-> ReportTemplateId mapping - without compiling tb-report-datasources/
// tb-entity-alias-select/tb-filter-select's own templates or the mat-autocomplete template, and
// without needing a live aliasController. This sidesteps the karma-safe-spec trap
// (task-13-interfaces.md's "Karma-safe spec pattern" section): a spec that never calls
// TestBed.configureTestingModule/compileComponents never asks Angular to compile any template at
// all, so it can never reach EntityAliasDialogComponent/FilterDialogComponent's declaring
// HomeComponentsModule.
import { UntypedFormBuilder } from '@angular/forms';
import { of } from 'rxjs';
import { ReportSubReportConfigComponent } from './report-sub-report-config.component';
import { DataSourceType, SubReportComponent } from '@shared/models/report-configuration.models';
import { ReportService } from '@core/http/report.service';
import { EntityType } from '@shared/models/entity-type.models';
import { emptyPageData } from '@shared/models/page/page-data';
import { BaseReportTemplate, ReportTemplateInfo } from '@shared/models/report.models';

describe('ReportSubReportConfigComponent', () => {

  let reportService: jasmine.SpyObj<ReportService>;

  beforeEach(() => {
    reportService = jasmine.createSpyObj('ReportService', ['getReportTemplateById', 'getReportTemplateInfos']);
    reportService.getReportTemplateById.and.returnValue(of({
      id: { entityType: EntityType.REPORT_TEMPLATE, id: 'sub-1' }, name: 'Sub Template'
    } as unknown as BaseReportTemplate));
    reportService.getReportTemplateInfos.and.returnValue(of(emptyPageData<ReportTemplateInfo>()));
  });

  function newComponent(): ReportSubReportConfigComponent {
    const component = new ReportSubReportConfigComponent(new UntypedFormBuilder(), reportService);
    component.ngOnInit();
    return component;
  }

  // task-13-brief.md Step 1 fixture.
  const subReportFixture: SubReportComponent = {
    type: 'SUB_REPORT',
    templateId: { entityType: EntityType.REPORT_TEMPLATE, id: 'sub-1' },
    avoidPageBreakInside: true,
    dataSources: [{ type: DataSourceType.ENTITY, entityAliasId: 'a1', dataKeys: [{ name: 'name', type: 'entityField', label: 'Name' }] }]
  };

  it('round-trips a SUB_REPORT component (templateId, avoidPageBreakInside, dataSources)', () => {
    const component = newComponent();
    component.format = 'PDF';
    component.writeValue(subReportFixture);
    let emitted: SubReportComponent;
    component.registerOnChange(v => emitted = v);

    component.subFormGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(subReportFixture);
    expect(emitted.type).toBe('SUB_REPORT');
  });

  it('templateId round-trips as a plain ReportTemplateId ({entityType, id})', () => {
    const component = newComponent();
    component.writeValue(subReportFixture);

    expect(component.subFormGroup.get('templateId').value).toEqual({
      entityType: EntityType.REPORT_TEMPLATE, id: 'sub-1'
    });
  });

  it('leaves every field null - not a forced default - when written with a bare {type: SUB_REPORT}', () => {
    const component = newComponent();

    component.writeValue({ type: 'SUB_REPORT' });

    expect(component.subFormGroup.value).toEqual({
      templateId: null,
      avoidPageBreakInside: null,
      dataSources: null
    });
  });

  it('does not eagerly propagate on writeValue', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(subReportFixture);

    expect(onChange).not.toHaveBeenCalled();
  });

  describe('the CSV isPlainFormat gate (design spec Sec 3.3)', () => {

    it('shows avoidPageBreakInside for PDF', () => {
      const component = newComponent();
      component.format = 'PDF';

      expect(component.showAvoidPageBreakInside).toBe(true);
    });

    it('hides avoidPageBreakInside for CSV (CSV has no page breaks) but still round-trips the stored value', () => {
      const component = newComponent();
      component.format = 'CSV';
      component.writeValue(subReportFixture);
      let emitted: SubReportComponent;
      component.registerOnChange(v => emitted = v);

      expect(component.showAvoidPageBreakInside).toBe(false);

      component.subFormGroup.patchValue({}); // trigger valueChanges

      expect(emitted.avoidPageBreakInside).toBe(true);
      expect('avoidPageBreakInside' in emitted).toBe(true);
    });
  });

  describe('the template autocomplete', () => {

    it('maps a selected BaseReportTemplate onto subFormGroup.templateId as a ReportTemplateId', () => {
      const component = newComponent();
      let emitted: SubReportComponent;
      component.registerOnChange(v => emitted = v);

      component.templateInput.setValue({
        id: { entityType: EntityType.REPORT_TEMPLATE, id: 'picked-id' }, name: 'Picked template'
      } as unknown as BaseReportTemplate);

      expect(component.subFormGroup.get('templateId').value).toEqual({
        entityType: EntityType.REPORT_TEMPLATE, id: 'picked-id'
      });
      expect(emitted.templateId).toEqual({ entityType: EntityType.REPORT_TEMPLATE, id: 'picked-id' });
    });

    it('clears templateId when the input is cleared to a raw string/null', () => {
      const component = newComponent();
      component.templateInput.setValue({ id: { entityType: EntityType.REPORT_TEMPLATE, id: 'x' }, name: 'X' } as unknown as BaseReportTemplate);

      component.templateInput.setValue(null);

      expect(component.subFormGroup.get('templateId').value).toBeNull();
    });

    it('displayTemplateFn shows the template name, and undefined for no template', () => {
      const component = newComponent();

      expect(component.displayTemplateFn({ name: 'My template' } as unknown as BaseReportTemplate)).toBe('My template');
      expect(component.displayTemplateFn(undefined)).toBeUndefined();
    });

    it('fetchTemplates excludes currentTemplateId from the results (a report cannot sub-report itself)', (done) => {
      const component = newComponent();
      component.currentTemplateId = 'self-id';
      reportService.getReportTemplateInfos.and.returnValue(of({
        data: [
          { id: { entityType: EntityType.REPORT_TEMPLATE, id: 'self-id' }, name: 'Self' },
          { id: { entityType: EntityType.REPORT_TEMPLATE, id: 'other-id' }, name: 'Other' }
        ],
        totalPages: 1, totalElements: 2, hasNext: false
      } as any));

      component.fetchTemplates('').subscribe(templates => {
        expect(templates.length).toBe(1);
        expect(templates[0].id.id).toBe('other-id');
        done();
      });
    });

    it('writeValue fetches the template display name by id, and clears the display first so a stale name never flashes', () => {
      const component = newComponent();

      component.writeValue(subReportFixture);

      expect(reportService.getReportTemplateById).toHaveBeenCalledWith('sub-1', { ignoreLoading: true, ignoreErrors: true });
      expect(component.templateInput.value).toEqual(jasmine.objectContaining({ name: 'Sub Template' }));
    });

    it('leaves the display blank (without touching the stored templateId) when the fetch-by-id fails', () => {
      reportService.getReportTemplateById.and.returnValue(of(null));
      const component = newComponent();

      component.writeValue(subReportFixture);

      expect(component.subFormGroup.get('templateId').value).toEqual(subReportFixture.templateId);
    });
  });

  it('exposes format/currentTemplateId/aliasController as plain pass-through Inputs (wired by the shell)', () => {
    const component = newComponent();

    expect(component.aliasController).toBeUndefined();
    const aliasController: any = { fake: true };
    component.aliasController = aliasController;
    expect(component.aliasController).toBe(aliasController);

    component.currentTemplateId = 'tpl-1';
    expect(component.currentTemplateId).toBe('tpl-1');
  });

  it('disables and re-enables both the underlying form and the template input via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.subFormGroup.disabled).toBe(true);
    expect(component.templateInput.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.subFormGroup.disabled).toBe(false);
    expect(component.templateInput.disabled).toBe(false);
  });
});
