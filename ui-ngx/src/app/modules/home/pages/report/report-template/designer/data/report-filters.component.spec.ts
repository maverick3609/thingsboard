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
// rationale (fake ReportAliasCallbacksFactory as a plain constructor param keeps this file's own
// imports clear of EntityAliasDialogComponent/FilterDialogComponent, which is what makes it
// karma-runnable). The keyFilters conversion boundary (FilterDialogComponent operates on platform
// KeyFilterInfo[]; config.filters[] must always hold report KeyFilter[]) is proven for real in
// report-alias-callbacks.spec.ts, against the exact production data-flow code
// (report-alias-dialog-flow.ts), parametrized by a fake dialog class. This spec's job is the
// component's OWN wiring: config -> aliasController, list rendering, and add/edit/delete correctly
// delegating to (and reacting to) the injected callbacks.
//
// ReportAliasCallbacks.createFilter/editFilter return PLATFORM Filter (query.models.ts,
// keyFilters: KeyFilterInfo[] - matching FilterSelectCallbacks' own contract), which can't be pushed
// directly into config.filters (report Filter[], keyFilters: KeyFilter[] - a genuinely different
// shape, not just an optional-vs-required difference the way EntityAlias is). So the fake
// createFilter/editFilter below route their "on success" write-back through a REAL
// createReportAliasController (Task 9's shim - no dialog dependency, safe here) exactly as the real
// factory does, which performs the actual KeyFilterInfo[]->KeyFilter[] conversion. That conversion
// itself is what report-alias-callbacks.spec.ts proves in detail; reusing the real shim here (rather
// than hand-rolling a second conversion in the test) means this spec can't silently diverge from it.
import { of } from 'rxjs';
import { ReportFiltersComponent } from './report-filters.component';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { EntityKeyType, EntityKeyValueType, Filter, KeyFilterInfo } from '@shared/models/query/query.models';
import { ReportAliasCallbacks, ReportAliasCallbacksFactory } from './report-alias-callbacks.models';
import { createReportAliasController } from './report-alias-controller';

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

function newComponent(config: ReportTemplateConfigModel): {
  component: ReportFiltersComponent;
  callbacks: jasmine.SpyObj<ReportAliasCallbacks>;
} {
  const callbacks: jasmine.SpyObj<ReportAliasCallbacks> =
    jasmine.createSpyObj('ReportAliasCallbacks', ['createEntityAlias', 'editEntityAlias', 'createFilter', 'editFilter']);
  callbacks.createFilter.and.returnValue(of(null));
  callbacks.editFilter.and.returnValue(of(null));
  const factory: ReportAliasCallbacksFactory = () => callbacks;
  const component = new ReportFiltersComponent(factory);
  component.config = config;
  component.ngOnInit();
  return {component, callbacks};
}

describe('ReportFiltersComponent', () => {

  it('lists config.filters on init', () => {
    const {component} = newComponent(configWithOneFilter());

    expect(component.filters.length).toBe(1);
    expect(component.filters[0].filter).toBe('High severity');
  });

  it('addFilter() calls callbacks.createFilter(), and when it resolves the platform-shaped result lands in config.filters[] in REPORT {predicate} keyFilters shape', () => {
    const config = configWithOneFilter();
    const {component, callbacks} = newComponent(config);
    // A SEPARATE aliasController instance over the SAME config - Task 9's shim is a stateless proxy
    // (see its header), so this is functionally identical to the one the component built internally.
    const aliasController = createReportAliasController(() => config);
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
    callbacks.createFilter.and.callFake(() => {
      const filters = aliasController.getFilters();
      filters[newPlatformFilter.id] = newPlatformFilter;
      aliasController.updateFilters(filters);
      return of(newPlatformFilter);
    });

    component.addFilter();

    expect(callbacks.createFilter).toHaveBeenCalledWith('');
    expect(config.filters.length).toBe(2);
    expect(component.filters.length).toBe(2);
    const stored = config.filters.find(f => f.id === 'f2');
    expect(stored.filter).toBe('Low severity');
    // THE conversion boundary: config.filters[] must hold report {predicate} shape, never the
    // platform {predicates[]} shape the fake callback resolved with.
    expect((stored.keyFilters[0] as any).predicate).toBeDefined();
    expect((stored.keyFilters[0] as any).predicates).toBeUndefined();
    expect(stored.keyFilters[0].predicate).toEqual({type: 'STRING', operation: 'EQUAL', value: {defaultValue: 'LOW'}} as any);
  });

  it('addFilter() does not refresh (or change config) when the dialog is cancelled (callbacks resolves null)', () => {
    const config = configWithOneFilter();
    const {component, callbacks} = newComponent(config);

    component.addFilter();

    expect(callbacks.createFilter).toHaveBeenCalled();
    expect(config.filters.length).toBe(1);
    expect(component.filters.length).toBe(1);
  });

  it('editFilter() calls callbacks.editFilter() with the selected filter, and when it resolves the result replaces the entry in config, still REPORT-shaped', () => {
    const config = configWithOneFilter();
    const {component, callbacks} = newComponent(config);
    const aliasController = createReportAliasController(() => config);
    const edited: Filter = {
      ...component.filters[0],
      filter: 'High severity renamed',
      keyFilters: [
        {
          key: {type: EntityKeyType.ATTRIBUTE, key: 'severity'},
          valueType: EntityKeyValueType.STRING,
          predicates: [{keyFilterPredicate: {type: 'STRING', operation: 'NOT_EQUAL', value: {defaultValue: 'LOW'}} as any, userInfo: null}]
        } as KeyFilterInfo
      ]
    };
    callbacks.editFilter.and.callFake(() => {
      const filters = aliasController.getFilters();
      filters[edited.id] = edited;
      aliasController.updateFilters(filters);
      return of(edited);
    });

    component.editFilter(component.filters[0]);

    expect(callbacks.editFilter).toHaveBeenCalledWith(jasmine.objectContaining({id: 'f1', filter: 'High severity'}));
    expect(config.filters.length).toBe(1);
    expect(config.filters[0].filter).toBe('High severity renamed');
    expect((config.filters[0].keyFilters[0] as any).predicate.operation).toBe('NOT_EQUAL');
    expect((config.filters[0].keyFilters[0] as any).predicates).toBeUndefined();
  });

  it('deleteFilter() removes the entry from config.filters without calling any callback', () => {
    const config = configWithOneFilter();
    const {component, callbacks} = newComponent(config);

    component.deleteFilter(component.filters[0]);

    expect(callbacks.createFilter).not.toHaveBeenCalled();
    expect(callbacks.editFilter).not.toHaveBeenCalled();
    expect(config.filters.length).toBe(0);
    expect(component.filters.length).toBe(0);
  });
});
