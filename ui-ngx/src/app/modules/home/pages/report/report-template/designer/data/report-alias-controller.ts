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

// Bounded IAliasController shim (core/api/widget-api.models.ts) for the report designer. The
// platform's tb-entity-alias-select and tb-filter-select (settings/common/alias|filter/*) both
// require a live, non-null `aliasController: IAliasController` @Input() - entity-alias-select's
// ngOnInit subscribes to `aliasController.entityAliasesChanged` unconditionally, so ANY consumer of
// those widgets NPEs without one. The designer has no dashboard/AliasController (it edits a report
// template, not a dashboard) - this factory builds one backed entirely by the report template's OWN
// `entityAliases[]`/`filters[]` (report-configuration.models.ts's ReportTemplateConfigBase), so the
// two picker widgets can be reused as-is. Consumed by Task 8 (datasource composite), Task 10 (manage
// aliases/filters UI), Task 11 (table panels).
//
// NOTE ON MEMBER COUNT: the task brief that spawned this file says IAliasController has "17
// members"; the interface as it stands in this checkout (widget-api.models.ts) actually declares
// 22 (3 Observable properties + 19 methods) - every one of them is implemented below, REAL or
// no-op. Flagging the discrepancy rather than silently reconciling it.
//
// REAL vs NO-OP split (see the two banner comments below for the full member list):
//  - REAL: getEntityAliases/getFilters/updateEntityAliases/updateFilters/entityAliasesChanged/
//    filtersChanged - the actual array<->map reshape the designer needs, so the pickers can list,
//    select, and (via their create/edit-alias/filter callbacks, wired by later tasks) persist
//    aliases/filters. Plus getEntityAliasId/getFilterInfo/getKeyFilters - cheap pure lookups over
//    the same data that OTHER reused CVAs in this family call (value-source.component.ts,
//    data-key-input.component.ts - see c2-reuse-cva-notes.md's tb-data-keys section), so they're
//    implemented for real rather than stubbed, at no extra cost.
//  - NO-OP: everything that means "resolve an alias/datasource/target-device to a live entity",
//    "per-viewer filter override", or "dashboard state changed" - concepts that don't exist for a
//    report *template* being authored (no live dashboard, no viewing user, no dashboard state).
//    Preview renders server-side (POST /api/v2/report/test, R1); the designer itself never resolves
//    anything. Each no-op returns the emptiest value its signature allows and never throws.
//
// KNOWN CAST (getFilters()/updateFilters() only): report Filter.keyFilters is typed
// data.query.KeyFilter[] (report-configuration.models.ts imports KeyFilter directly from
// query.models - the `{key, valueType, predicate}` shape). The platform's FilterInfo.keyFilters
// (query.models.ts) is instead typed KeyFilterInfo[] - a DIFFERENT, richer `{key, valueType,
// predicates: KeyFilterPredicateInfo[]}` shape used by the template-predicate "manage filters" UI.
// These are structurally incompatible (KeyFilterInfo has no `predicate` field at all), so building a
// platform FilterInfo from a report Filter needs an `as unknown as` cast on this one field. Neither
// tb-filter-select nor tb-entity-alias-select ever reads into .keyFilters (confirmed by reading both
// components in full - they only touch .id/.filter/.alias), so in THIS shim the cast carries opaque,
// identity-preserving cargo, never interpreted. getKeyFilters() below deliberately bypasses this cast
// and reads config.filters[].keyFilters directly, so any REAL keyFilters consumer always sees the
// authentic report KeyFilter[] shape, never the cast placeholder.
//
// entityAlias.filter needs NO cast: report EntityAlias.filter is data.query.EntityFilter (same
// import-straight-from-query.models pattern), and the platform's EntityAliasInfo.filter is
// EntityAliasFilter = EntityFilter & { resolveMultiple?: boolean } (alias.models.ts) - a strict
// structural superset with one extra OPTIONAL field, so EntityFilter values pass through in both
// directions without a cast.

import { NEVER, Observable, of, Subject } from 'rxjs';
import { AliasInfo, IAliasController } from '@core/api/widget-api.models';
import { Datasource, TargetDevice } from '@shared/models/widget.models';
import { EntityInfo } from '@app/shared/models/entity.models';
import { EntityAliases } from '@shared/models/alias.models';
import { Filter, FilterInfo, Filters, KeyFilter } from '@shared/models/query/query.models';
import { isEqual } from '@core/utils';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';

class ReportAliasController implements IAliasController {

  private readonly entityAliasesChangedSubject = new Subject<string[]>();
  readonly entityAliasesChanged: Observable<string[]> = this.entityAliasesChangedSubject.asObservable();

  private readonly filtersChangedSubject = new Subject<string[]>();
  readonly filtersChanged: Observable<string[]> = this.filtersChangedSubject.asObservable();

  // Resolution never happens in the designer (see header) - this simply never emits.
  readonly entityAliasResolved: Observable<string> = NEVER;

  constructor(private readonly getConfig: () => ReportTemplateConfigModel) {
  }

  // ==================== REAL: backed by config.entityAliases[] / config.filters[] ====================

  getEntityAliases(): EntityAliases {
    const result: EntityAliases = {};
    for (const alias of this.getConfig().entityAliases ?? []) {
      const id = alias?.id;
      if (!id) {
        continue;
      }
      result[id] = {
        id,
        alias: alias.alias ?? '',
        filter: alias.filter ?? {}
      };
    }
    return result;
  }

  updateEntityAliases(entityAliases: EntityAliases): void {
    const changedAliasIds = diffChangedIds(this.getEntityAliases(), entityAliases);
    this.getConfig().entityAliases = Object.keys(entityAliases).map((id) => ({
      id,
      alias: entityAliases[id].alias,
      filter: entityAliases[id].filter
    }));
    if (changedAliasIds.length) {
      this.entityAliasesChangedSubject.next(changedAliasIds);
    }
  }

  getEntityAliasId(aliasName: string): string {
    const entityAliases = this.getEntityAliases();
    for (const aliasId of Object.keys(entityAliases)) {
      if (entityAliases[aliasId].alias === aliasName) {
        return aliasId;
      }
    }
    return null;
  }

  getFilters(): Filters {
    const result: Filters = {};
    for (const filter of this.getConfig().filters ?? []) {
      const id = filter?.id;
      if (!id) {
        continue;
      }
      result[id] = {
        id,
        filter: filter.filter ?? '',
        editable: true,
        // Opaque cast - see header KNOWN CAST note. getKeyFilters() never reads this field back.
        keyFilters: (filter.keyFilters ?? []) as unknown as FilterInfo['keyFilters']
      };
    }
    return result;
  }

  updateFilters(filters: Filters): void {
    const changedFilterIds = diffChangedIds(this.getFilters(), filters);
    this.getConfig().filters = Object.keys(filters).map((id) => ({
      id,
      filter: filters[id].filter,
      // Reverse of the same opaque cast (header KNOWN CAST note).
      keyFilters: (filters[id].keyFilters ?? []) as unknown as KeyFilter[]
    }));
    if (changedFilterIds.length) {
      this.filtersChangedSubject.next(changedFilterIds);
    }
  }

  getFilterInfo(filterId: string): FilterInfo {
    return this.getFilters()[filterId];
  }

  getKeyFilters(filterId: string): Array<KeyFilter> {
    // Reads config.filters[] directly (NOT getFilterInfo()/getFilters()) so this returns the
    // authentic report KeyFilter[] rather than round-tripping it through the FilterInfo cast above.
    const filter = (this.getConfig().filters ?? []).find((f) => f.id === filterId);
    return filter?.keyFilters ?? [];
  }

  // ==================== NO-OP: no live entity/dashboard resolution in the designer ====================

  getUserFilters(): Filters {
    // No per-viewer filter-override layer for a report *template* being authored (there is no
    // "viewing user" of a template) - always empty, so getFilterInfo-style callers fall through to
    // the real template filter.
    return {};
  }

  updateUserFilter(_filter: Filter): void {
  }

  getAliasInfo(_aliasId: string): Observable<AliasInfo> {
    return of({});
  }

  getInstantAliasInfo(_aliasId: string): AliasInfo {
    return undefined;
  }

  resolveSingleEntityInfo(_aliasId: string): Observable<EntityInfo> {
    return of(null);
  }

  resolveSingleEntityInfoForDeviceId(_deviceId: string): Observable<EntityInfo> {
    return of(null);
  }

  resolveSingleEntityInfoForTargetDevice(_targetDevice: TargetDevice): Observable<EntityInfo> {
    return of(null);
  }

  resolveDatasources(_datasources: Array<Datasource>, _singleEntity?: boolean, _pageSize?: number): Observable<Array<Datasource>> {
    return of([]);
  }

  resolveAlarmSource(_alarmSource: Datasource): Observable<Datasource> {
    return of(null);
  }

  updateCurrentAliasEntity(_aliasId: string, _currentEntity: EntityInfo): void {
  }

  updateAliases(_aliasIds?: Array<string>): void {
  }

  dashboardStateChanged(): void {
  }
}

function diffChangedIds<T>(previous: Record<string, T>, next: Record<string, T>): string[] {
  const changed: string[] = [];
  for (const id of Object.keys(next)) {
    if (!isEqual(next[id], previous[id])) {
      changed.push(id);
    }
  }
  for (const id of Object.keys(previous)) {
    if (!next[id]) {
      changed.push(id);
    }
  }
  return changed;
}

export function createReportAliasController(getConfig: () => ReportTemplateConfigModel): IAliasController {
  return new ReportAliasController(getConfig);
}
