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

// Plain instantiation (no TestBed) - see report-entity-aliases.component.spec.ts's header for the
// rationale. The distinguishing concern here is the keyFilters shape boundary: FilterDialogComponent
// operates on platform KeyFilterInfo[] ({predicates[]}), but config.filters[] must always end up
// holding report KeyFilter[] ({predicate}) - the "createFilter lands in config.filters[] in REPORT
// {predicate} keyFilters shape" assertion below is the load-bearing one.
import { of } from 'rxjs';
import { ReportFiltersComponent } from './report-filters.component';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { EntityKeyType, EntityKeyValueType, Filter, KeyFilterInfo } from '@shared/models/query/query.models';
import { FilterDialogComponent } from '@home/components/filter/filter-dialog.component';

function configWithOneFilter(): ReportTemplateConfigModel {
  return {
    ...newPdfReportTemplateConfig(),
    filters: [
      {
        id: 'f1',
        filter: 'High severity',
        keyFilters: [
          {
            key: {type: EntityKeyType.ATTRIBUTE, key: 'severity'},
            valueType: EntityKeyValueType.STRING,
            predicate: {type: 'STRING', operation: 'EQUAL', value: {defaultValue: 'HIGH'}} as any
          }
        ]
      }
    ]
  };
}

function newComponent(config: ReportTemplateConfigModel, dialog: jasmine.SpyObj<any>): ReportFiltersComponent {
  const component = new ReportFiltersComponent(dialog);
  component.config = config;
  component.ngOnInit();
  return component;
}

function fakeDialog(returnValue: any): jasmine.SpyObj<any> {
  const dialog = jasmine.createSpyObj('MatDialog', ['open']);
  dialog.open.and.returnValue({afterClosed: () => returnValue});
  return dialog;
}

describe('ReportFiltersComponent', () => {

  it('lists config.filters on init', () => {
    const config = configWithOneFilter();
    const component = newComponent(config, fakeDialog(of(null)));

    expect(component.filters.length).toBe(1);
    expect(component.filters[0].filter).toBe('High severity');
  });

  it('addFilter() opens FilterDialogComponent, and a platform-shaped result lands in config.filters[] in REPORT {predicate} keyFilters shape', () => {
    const config = configWithOneFilter();
    const newPlatformFilter: Filter = {
      id: 'f2',
      filter: 'Low severity',
      editable: true,
      keyFilters: [
        {
          key: {type: EntityKeyType.ATTRIBUTE, key: 'severity'},
          valueType: EntityKeyValueType.STRING,
          predicates: [{keyFilterPredicate: {type: 'STRING', operation: 'EQUAL', value: {defaultValue: 'LOW'}} as any, userInfo: null}]
        } as KeyFilterInfo
      ]
    };
    const dialog = fakeDialog(of(newPlatformFilter));
    const component = newComponent(config, dialog);

    component.addFilter();

    expect(dialog.open).toHaveBeenCalledWith(FilterDialogComponent, jasmine.objectContaining({
      data: jasmine.objectContaining({isAdd: true})
    }));
    const stored = config.filters.find(f => f.id === 'f2');
    expect(stored).toBeTruthy();
    expect(stored.filter).toBe('Low severity');
    // THE conversion boundary the brief calls out: config.filters[] must hold report {predicate}
    // shape, never the platform {predicates[]} shape FilterDialogComponent returned.
    expect((stored.keyFilters[0] as any).predicate).toEqual({type: 'STRING', operation: 'EQUAL', value: {defaultValue: 'LOW'}} as any);
    expect((stored.keyFilters[0] as any).predicates).toBeUndefined();
    expect(component.filters.length).toBe(2);
  });

  it('addFilter() does not change config.filters when the dialog is cancelled', () => {
    const config = configWithOneFilter();
    const dialog = fakeDialog(of(null));
    const component = newComponent(config, dialog);

    component.addFilter();

    expect(config.filters.length).toBe(1);
    expect(component.filters.length).toBe(1);
  });

  it('editFilter() opens FilterDialogComponent with isAdd false, and the result replaces the entry in config.filters, still in REPORT keyFilters shape', () => {
    const config = configWithOneFilter();
    const component0 = newComponent(config, fakeDialog(of(null)));
    const existing = component0.filters[0];
    const edited: Filter = {
      id: 'f1',
      filter: 'High severity renamed',
      editable: false,
      keyFilters: [
        {
          key: {type: EntityKeyType.ATTRIBUTE, key: 'severity'},
          valueType: EntityKeyValueType.STRING,
          predicates: [{keyFilterPredicate: {type: 'STRING', operation: 'NOT_EQUAL', value: {defaultValue: 'LOW'}} as any, userInfo: null}]
        } as KeyFilterInfo
      ]
    };
    const dialog = fakeDialog(of(edited));
    const component = newComponent(config, dialog);

    component.editFilter(existing);

    expect(dialog.open).toHaveBeenCalledWith(FilterDialogComponent, jasmine.objectContaining({
      data: jasmine.objectContaining({isAdd: false})
    }));
    expect(config.filters.length).toBe(1);
    expect(config.filters[0].filter).toBe('High severity renamed');
    expect((config.filters[0].keyFilters[0] as any).predicate.operation).toBe('NOT_EQUAL');
    expect((config.filters[0].keyFilters[0] as any).predicates).toBeUndefined();
  });

  it('deleteFilter() removes the entry from config.filters without opening a dialog', () => {
    const config = configWithOneFilter();
    const dialog = fakeDialog(of(null));
    const component = newComponent(config, dialog);

    component.deleteFilter(component.filters[0]);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(config.filters.length).toBe(0);
    expect(component.filters.length).toBe(0);
  });
});
