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
import {
  EntityKeyType,
  EntityKeyValueType,
  filterInfoToKeyFilters,
  Filters,
  isFilterEditable,
  keyFiltersToKeyFilterInfos
} from '@shared/models/query/query.models';

// The platform's keyFilter<->keyFilterInfo converters (query.models.ts) always materialize an
// explicit `value: undefined` key (they write `value: keyFilter.value` unconditionally) even when
// the source object never had a `value` key at all. Jasmine's toEqual treats "key absent" and "key
// present with value undefined" as DIFFERENT, so a byte-for-byte toEqual across a real conversion
// round-trip needs this normalization first. This is exactly what actually persists: the report
// model's own serializer (report-configuration.serializer.ts) is JSON.parse(JSON.stringify(model)),
// and JSON.stringify drops undefined-valued keys - so this normalization matches the real wire
// shape, not just a test convenience.
function normalizeJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

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

    it('updateEntityAliases() deep-clones its input - mutating the caller\'s object afterward does not reach config', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const newMap: EntityAliases = {
        a2: { id: 'a2', alias: 'Assets', filter: { type: AliasFilterType.entityType, entityType: EntityType.ASSET } }
      };
      controller.updateEntityAliases(newMap);
      newMap.a2.alias = 'Mutated';

      expect(config.entityAliases[0].alias).toBe('Assets');
    });

    it('getEntityAliasId() looks up an existing alias id by name, null when absent', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      expect(controller.getEntityAliasId('Devices')).toBe('a1');
      expect(controller.getEntityAliasId('nope')).toBeNull();
    });
  });

  describe('filters (REAL)', () => {

    it('getFilters() reflects config.filters, keyFilters CONVERTED to genuine platform KeyFilterInfo[]', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const filters = controller.getFilters();

      expect(Object.keys(filters)).toEqual(['f1']);
      expect(filters.f1.filter).toBe('High severity');
      // Load-bearing (implementation header point 2), not just a default value.
      expect(filters.f1.editable).toBe(false);
      // Genuine KeyFilterInfo[] - matches what the platform's OWN converter produces for the same
      // input (proves the real helper is used, not a reimplementation or a cast).
      expect(filters.f1.keyFilters).toEqual(keyFiltersToKeyFilterInfos(config.filters[0].keyFilters as any));
      expect(Array.isArray(filters.f1.keyFilters[0].predicates)).toBe(true);
      expect((filters.f1.keyFilters[0] as any).predicate).toBeUndefined();
    });

    it('getFilters()->updateFilters() round-trips keyFilters faithfully through the platform shape (not corrupted)', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      const originalKeyFilters = configWithAliasAndFilter().filters[0].keyFilters;

      // Read back through the platform (KeyFilterInfo) shape, as Task 10's filter-edit dialog would.
      const filterInfo = controller.getFilters().f1;
      expect(filterInfo.keyFilters[0].predicates.length).toBe(1);

      const emitted: string[][] = [];
      controller.filtersChanged.subscribe((ids) => emitted.push(ids));

      // Feed the platform-shaped FilterInfo straight back in, as an edit dialog would (only the
      // display name actually changes).
      const newMap: Filters = {
        f1: { ...filterInfo, filter: 'High severity renamed' }
      };
      controller.updateFilters(newMap);

      // config.filters[0].keyFilters is back to the ORIGINAL report {predicate} shape - NOT the
      // platform {predicates[]} shape blindly cast through (the CRITICAL review finding).
      expect(config.filters[0].filter).toBe('High severity renamed');
      expect(normalizeJson(config.filters[0].keyFilters)).toEqual(normalizeJson(originalKeyFilters) as any);
      expect(config.filters[0].keyFilters[0].predicate).toBeDefined();
      expect((config.filters[0].keyFilters[0] as any).predicates).toBeUndefined();
      expect(emitted.length).toBe(1);
      expect(emitted[0]).toEqual(['f1']);
    });

    it('updateFilters() does not emit when the map is unchanged', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);
      const emitted: string[][] = [];
      controller.filtersChanged.subscribe((ids) => emitted.push(ids));

      controller.updateFilters(controller.getFilters());

      expect(emitted.length).toBe(0);
    });

    it('updateFilters() deep-clones its input - mutating the caller\'s object afterward does not reach config', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const newMap: Filters = {
        f1: { id: 'f1', filter: 'Renamed', editable: false, keyFilters: [] }
      };
      controller.updateFilters(newMap);
      newMap.f1.filter = 'Mutated';

      expect(config.filters[0].filter).toBe('Renamed');
    });

    it('getKeyFilters() reads the authentic report KeyFilter[] directly off config, bypassing the KeyFilterInfo conversion', () => {
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

    // Regression test for the CRITICAL review finding: an earlier version of getFilters() cast a
    // report KeyFilter[] (shape {predicate}) directly into the FilterInfo.keyFilters slot (typed
    // KeyFilterInfo[], shape {predicates[]}) instead of converting it. These two platform helpers
    // are exactly what home/components/filter/filters-edit.component.ts:178-186 calls on
    // aliasController.getFilterInfo(...) - Task 10's expected reuse target - and both would have
    // thrown on the mislabeled shape ("Cannot read properties of undefined (reading
    // 'predicates'/'some')").
    it('isFilterEditable()/filterInfoToKeyFilters() (platform helpers reachable via Task 10 reuse) do not throw on a real FilterInfo', () => {
      const config = configWithAliasAndFilter();
      const controller = createReportAliasController(() => config);

      const filterInfo = controller.getFilterInfo('f1');

      expect(() => isFilterEditable(filterInfo)).not.toThrow();
      // editable:false short-circuits isFilterEditable before it ever touches .keyFilters - see
      // implementation header point 2 for why that's load-bearing, not just a default.
      expect(isFilterEditable(filterInfo)).toBe(false);

      let keyFilters: unknown;
      expect(() => { keyFilters = filterInfoToKeyFilters(filterInfo); }).not.toThrow();
      expect(normalizeJson(keyFilters)).toEqual(normalizeJson(config.filters[0].keyFilters) as any);
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
