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

// Plain instantiation (no TestBed), mirroring report-alias-controller.spec.ts: a fake MatDialog spy
// stands in for the real dialog service, and a REAL createReportAliasController wraps a REAL
// in-memory config, so the filter tests exercise the genuine keyFilters shape conversion end to end
// (not a mock of it) - this is the same conversion boundary report-alias-controller.spec.ts proves
// for getFilters()/updateFilters() directly; here it's proven through the callback functions a real
// caller (the manage-filters UI, tb-report-datasource's future callbacks Input) actually invokes.
import { of } from 'rxjs';
import { createReportAliasCallbacks } from './report-alias-callbacks';
import { createReportAliasController } from './report-alias-controller';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { AliasFilterType } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { EntityKeyType, EntityKeyValueType, Filter, KeyFilterInfo } from '@shared/models/query/query.models';

function configWithAliasAndFilter(): ReportTemplateConfigModel {
  return {
    ...newPdfReportTemplateConfig(),
    entityAliases: [
      {id: 'a1', alias: 'Devices', filter: {type: AliasFilterType.entityType, entityType: EntityType.DEVICE}}
    ],
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

function fakeDialog(returnValue: any): jasmine.SpyObj<any> {
  const dialog = jasmine.createSpyObj('MatDialog', ['open']);
  dialog.open.and.returnValue({afterClosed: () => returnValue});
  return dialog;
}

describe('report-alias-callbacks', () => {

  describe('createEntityAlias', () => {

    it('opens EntityAliasDialogComponent with isAdd true and the current entity aliases, and merges the result into config', (done) => {
      const config = configWithAliasAndFilter();
      const aliasController = createReportAliasController(() => config);
      const newAlias = {id: 'a2', alias: 'Assets', filter: {type: AliasFilterType.entityType, entityType: EntityType.ASSET}};
      const dialog = fakeDialog(of(newAlias));
      const callbacks = createReportAliasCallbacks(dialog, aliasController);

      callbacks.createEntityAlias('Assets', [EntityType.ASSET]).subscribe((result) => {
        expect(result).toBe(newAlias);
        const openArgs = dialog.open.calls.mostRecent().args;
        expect(openArgs[1].data.isAdd).toBe(true);
        expect(openArgs[1].data.allowedEntityTypes).toEqual([EntityType.ASSET]);
        expect(openArgs[1].data.entityAliases.a1).toBeTruthy();
        expect(openArgs[1].data.alias.alias).toBe('Assets');

        expect(config.entityAliases.find(a => a.id === 'a2')).toEqual(newAlias as any);
        expect(config.entityAliases.find(a => a.id === 'a1')).toBeTruthy();
        done();
      });
    });

    it('does not mutate config when the dialog is cancelled (afterClosed emits null)', (done) => {
      const config = configWithAliasAndFilter();
      const aliasController = createReportAliasController(() => config);
      const dialog = fakeDialog(of(null));
      const callbacks = createReportAliasCallbacks(dialog, aliasController);

      callbacks.createEntityAlias('x', undefined).subscribe((result) => {
        expect(result).toBeNull();
        expect(config.entityAliases.length).toBe(1);
        done();
      });
    });
  });

  describe('editEntityAlias', () => {

    it('opens EntityAliasDialogComponent with isAdd false and a deep-cloned alias, and replaces the entry in config by id', (done) => {
      const config = configWithAliasAndFilter();
      const aliasController = createReportAliasController(() => config);
      const original = aliasController.getEntityAliases().a1;
      const edited = {...original, alias: 'Devices renamed'};
      const dialog = fakeDialog(of(edited));
      const callbacks = createReportAliasCallbacks(dialog, aliasController);

      callbacks.editEntityAlias(original, undefined).subscribe(() => {
        const openArgs = dialog.open.calls.mostRecent().args;
        expect(openArgs[1].data.isAdd).toBe(false);
        expect(openArgs[1].data.alias).toEqual(original as any);
        expect(openArgs[1].data.alias).not.toBe(original);

        expect(config.entityAliases.length).toBe(1);
        expect(config.entityAliases[0].alias).toBe('Devices renamed');
        done();
      });
    });
  });

  describe('createFilter / editFilter - keyFilters conversion boundary', () => {

    it('createFilter opens FilterDialogComponent with an empty platform-shaped filter, and a platform-shaped result lands in config.filters[] in REPORT {predicate} shape', (done) => {
      const config = configWithAliasAndFilter();
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
      const dialog = fakeDialog(of(newPlatformFilter));
      const callbacks = createReportAliasCallbacks(dialog, aliasController);

      callbacks.createFilter('Low severity').subscribe(() => {
        const openArgs = dialog.open.calls.mostRecent().args;
        expect(openArgs[1].data.isAdd).toBe(true);
        expect(openArgs[1].data.filter).toEqual({id: null, filter: 'Low severity', keyFilters: [], editable: true} as any);
        // The uniqueness list handed to the dialog is the platform-shaped map (Task 9 shim), not a
        // raw report array - f1's keyFilters there must already be KeyFilterInfo[] (predicates), not
        // report KeyFilter[] (predicate), or the dialog's own duplicate-name check would be handed a
        // mislabeled shape.
        expect(Array.isArray(openArgs[1].data.filters.f1.keyFilters[0].predicates)).toBe(true);

        const stored = config.filters.find(f => f.id === 'f2');
        expect(stored).toBeTruthy();
        expect(stored.filter).toBe('Low severity');
        // THE conversion boundary: config.filters[] must hold report {predicate} shape, never the
        // platform {predicates[]} shape the dialog returned.
        expect((stored.keyFilters[0] as any).predicate).toBeDefined();
        expect((stored.keyFilters[0] as any).predicates).toBeUndefined();
        expect(stored.keyFilters[0].predicate).toEqual({type: 'STRING', operation: 'EQUAL', value: {defaultValue: 'LOW'}} as any);
        done();
      });
    });

    it('editFilter opens FilterDialogComponent with isAdd false and a deep-cloned platform-shaped filter (not the same reference), and writes the edited result back in REPORT shape', (done) => {
      const config = configWithAliasAndFilter();
      const aliasController = createReportAliasController(() => config);
      const platformFilter = aliasController.getFilters().f1;
      const edited: Filter = {...platformFilter, filter: 'High severity renamed'};
      const dialog = fakeDialog(of(edited));
      const callbacks = createReportAliasCallbacks(dialog, aliasController);

      callbacks.editFilter(platformFilter).subscribe(() => {
        const openArgs = dialog.open.calls.mostRecent().args;
        expect(openArgs[1].data.isAdd).toBe(false);
        expect(openArgs[1].data.filter).toEqual(platformFilter as any);
        expect(openArgs[1].data.filter).not.toBe(platformFilter);

        expect(config.filters.length).toBe(1);
        expect(config.filters[0].filter).toBe('High severity renamed');
        expect(config.filters[0].keyFilters[0].predicate).toBeDefined();
        expect((config.filters[0].keyFilters[0] as any).predicates).toBeUndefined();
        done();
      });
    });
  });
});
