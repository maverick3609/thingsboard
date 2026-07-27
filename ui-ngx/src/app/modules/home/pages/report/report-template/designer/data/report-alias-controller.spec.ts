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

// Pure-module spec (no TestBed) - report-alias-controller.ts is a plain factory/class, not an
// Angular component, mirroring report-configuration.serializer.spec.ts's style.

import { createReportAliasController } from './report-alias-controller';
import { newPdfReportTemplateConfig, ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { AliasFilterType, EntityAliases } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { EntityKeyType, EntityKeyValueType, Filters } from '@shared/models/query/query.models';

function configWithAliasAndFilter(): ReportTemplateConfigModel {
  return {
    ...newPdfReportTemplateConfig(),
    entityAliases: [
      { id: 'a1', alias: 'Devices', filter: { type: AliasFilterType.entityType, entityType: EntityType.DEVICE } }
    ],
    filters: [
      {
        id: 'f1',
        filter: 'High severity',
        keyFilters: [
          {
            key: { type: EntityKeyType.ATTRIBUTE, key: 'severity' },
            valueType: EntityKeyValueType.STRING,
            predicate: { type: 'STRING', operation: 'EQUAL', value: { defaultValue: 'HIGH' } } as any
          }
        ]
      }
    ]
  };
}

describe('report-alias-controller', () => {

  describe('entity aliases (REAL)', () => {

    it('getEntityAliases() reflects config.entityAliases, filter passed through without transformation', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const aliases = controller.getEntityAliases();

      expect(Object.keys(aliases)).toEqual(['a1']);
      expect(aliases.a1.alias).toBe('Devices');
      expect(aliases.a1.filter).toEqual(config.entityAliases[0].filter as any);
    });

    it('updateEntityAliases() writes back to config.entityAliases[] and emits entityAliasesChanged', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      const emitted: string[][] = [];
      controller.entityAliasesChanged.subscribe((ids) => emitted.push(ids));

      const newMap: EntityAliases = {
        a2: { id: 'a2', alias: 'Assets', filter: { type: AliasFilterType.entityType, entityType: EntityType.ASSET } }
      };
      controller.updateEntityAliases(newMap);

      expect(config.entityAliases.length).toBe(1);
      expect(config.entityAliases[0]).toEqual({ id: 'a2', alias: 'Assets', filter: newMap.a2.filter as any });
      expect(controller.getEntityAliases().a2).toBeTruthy();
      expect(controller.getEntityAliases().a1).toBeUndefined();
      expect(emitted.length).toBe(1);
      expect(emitted[0].sort()).toEqual(['a1', 'a2']);
    });

    it('updateEntityAliases() does not emit when the map is unchanged', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      const emitted: string[][] = [];
      controller.entityAliasesChanged.subscribe((ids) => emitted.push(ids));

      controller.updateEntityAliases(controller.getEntityAliases());

      expect(emitted.length).toBe(0);
    });

    it('getEntityAliasId() looks up an existing alias id by name, null when absent', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      expect(controller.getEntityAliasId('Devices')).toBe('a1');
      expect(controller.getEntityAliasId('nope')).toBeNull();
    });
  });

  describe('filters (REAL)', () => {

    it('getFilters() reflects config.filters, keyFilters round-trip content unchanged', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const filters = controller.getFilters();

      expect(Object.keys(filters)).toEqual(['f1']);
      expect(filters.f1.filter).toBe('High severity');
      expect(filters.f1.editable).toBe(true);
      expect(filters.f1.keyFilters as any).toEqual(config.filters[0].keyFilters as any);
    });

    it('updateFilters() writes back to config.filters[] and emits filtersChanged', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      const emitted: string[][] = [];
      controller.filtersChanged.subscribe((ids) => emitted.push(ids));

      const newKeyFilters = [
        { key: { type: EntityKeyType.ATTRIBUTE, key: 'active' }, valueType: EntityKeyValueType.BOOLEAN, predicates: [] }
      ];
      const newMap: Filters = {
        f1: { id: 'f1', filter: 'High severity renamed', editable: true, keyFilters: newKeyFilters }
      };
      controller.updateFilters(newMap);

      expect(config.filters.length).toBe(1);
      expect(config.filters[0].filter).toBe('High severity renamed');
      expect(config.filters[0].keyFilters as any).toEqual(newKeyFilters as any);
      expect(emitted.length).toBe(1);
      expect(emitted[0]).toEqual(['f1']);
    });

    it('getKeyFilters() reads the authentic report KeyFilter[] directly off config, bypassing the FilterInfo cast', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      expect(controller.getKeyFilters('f1')).toEqual(config.filters[0].keyFilters);
      expect(controller.getKeyFilters('missing-id')).toEqual([]);
    });

    it('getFilterInfo() returns the FilterInfo for a known id, undefined for an unknown one', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      expect(controller.getFilterInfo('f1').filter).toBe('High severity');
      expect(controller.getFilterInfo('missing-id')).toBeUndefined();
    });
  });

  describe('no-op members', () => {

    it('never throw when called, and use the emptiest value their signature allows', (done) => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      expect(() => controller.dashboardStateChanged()).not.toThrow();
      expect(() => controller.updateAliases(['a1'])).not.toThrow();
      expect(() => controller.updateAliases()).not.toThrow();
      expect(() => controller.updateCurrentAliasEntity('a1', {} as any)).not.toThrow();
      expect(() => controller.updateUserFilter({ id: 'f1', filter: 'x', editable: true, keyFilters: [] })).not.toThrow();

      expect(controller.getUserFilters()).toEqual({});
      expect(controller.getInstantAliasInfo('a1')).toBeUndefined();

      let pending = 6;
      const settle = () => { if (--pending === 0) { done(); } };

      controller.resolveDatasources([]).subscribe((res) => { expect(res).toEqual([]); settle(); });
      controller.resolveAlarmSource({} as any).subscribe((res) => { expect(res).toBeNull(); settle(); });
      controller.resolveSingleEntityInfo('a1').subscribe((res) => { expect(res).toBeNull(); settle(); });
      controller.resolveSingleEntityInfoForDeviceId('d1').subscribe((res) => { expect(res).toBeNull(); settle(); });
      controller.resolveSingleEntityInfoForTargetDevice({} as any).subscribe((res) => { expect(res).toBeNull(); settle(); });
      controller.getAliasInfo('a1').subscribe((res) => { expect(res).toEqual({}); settle(); });
    });

    it('entityAliasResolved never emits (resolution never happens)', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      let emitted = false;

      controller.entityAliasResolved.subscribe(() => { emitted = true; });

      expect(emitted).toBe(false);
    });
  });
});
