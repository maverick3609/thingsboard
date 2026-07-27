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
// keyFilters CONVERSION (getFilters()/updateFilters() only): report Filter.keyFilters is typed
// data.query.KeyFilter[] (report-configuration.models.ts imports KeyFilter directly from
// query.models - the plain `{key, valueType, value?, predicate}` shape). The platform's
// FilterInfo.keyFilters (query.models.ts) is instead typed KeyFilterInfo[] - a DIFFERENT, richer
// `{key, valueType, value?, predicates: KeyFilterPredicateInfo[]}` shape used by the "manage
// filters" template-predicate UI (home/components/filter/filters-edit.component.ts, which Task 10
// is expected to reuse - it already calls getFilters()/getFilterInfo()/isFilterEditable() at
// filters-edit.component.ts:178-186). KeyFilterInfo has no `predicate` field at all, so
// isFilterEditable/filterInfoToKeyFilters (query.models.ts:550-620) throw
// "Cannot read properties of undefined (reading 'predicates'/'some')" if handed a report KeyFilter
// mislabeled as a KeyFilterInfo. An earlier version of this file used an `as unknown as` cast here -
// WRONG, caught in review (task-9-report.md fix section) - now replaced with the platform's own
// bidirectional converters, used verbatim (not reimplemented): getFilters()/getFilterInfo() build a
// genuine KeyFilterInfo[] via keyFiltersToKeyFilterInfos(); updateFilters() converts back to a
// genuine report KeyFilter[] via keyFilterInfosToKeyFilters().
//
// The conversion is real but LOSSY in two narrow, accepted ways:
//  1. keyFiltersToKeyFilterInfos GROUPS KeyFilter entries sharing the same key+type+valueType into
//     one KeyFilterInfo with multiple predicates[] (query.models.ts:524-548). Converting back
//     expands each group - the AND-set of predicates round-trips faithfully, but if the ORIGINAL
//     array interleaved same-key entries with other keys between them, their relative ARRAY ORDER
//     can change (grouped entries end up adjacent). Harmless for KeyFilter's actual use (an AND-set,
//     not an ordered sequence) - this is the platform's own pre-existing helper behavior, not
//     something introduced here.
//  2. The converted KeyFilterPredicateInfo.userInfo is always `null`
//     (keyFilterPredicateToKeyFilterPredicateInfo, query.models.ts:582-600, never populates it from
//     a plain KeyFilterPredicate) - report filters carry no per-predicate "user-editable value"
//     authoring metadata to draw from.
//
// Point 2 is why getFilters()/getFilterInfo() set `editable: false` below, and it's load-bearing,
// not cosmetic: isFilterEditable() only walks into filter.keyFilters (and from there into
// isPredicateInfoEditable's `predicateInfo.userInfo.editable`) when filter.editable is true
// (query.models.ts:602-608). Since userInfo is always null post-conversion, reaching that branch
// null-derefs regardless of how faithfully keyFilters itself is shaped
// ("Cannot read properties of null (reading 'editable')", query.models.ts:619).
// `editable: false` short-circuits isFilterEditable to `false` before that line is ever reached -
// correct anyway, since an authored report template has no "viewer can tweak this predicate's value
// live" concept for tb-filters-edit's UserFilterDialogComponent to attach to.
//
// getKeyFilters() deliberately does NOT run filterInfoToKeyFilters(getFilterInfo(id)) (the "obvious"
// idiom, now that the shape is genuinely correct) - it keeps reading config.filters[].keyFilters
// directly. Simpler, and it sidesteps the grouping/reorder nuance in point 1 above entirely (a
// direct read can never reorder anything) for a member whose entire job is "hand back the
// authoritative KeyFilter[]".
//
// entityAlias.filter needs NO conversion: report EntityAlias.filter is data.query.EntityFilter (same
// import-straight-from-query.models pattern), and the platform's EntityAliasInfo.filter is
// EntityAliasFilter = EntityFilter & { resolveMultiple?: boolean } (alias.models.ts) - a strict
// structural superset with one extra OPTIONAL field, so EntityFilter values pass through in both
// directions unchanged.
//
// updateEntityAliases()/updateFilters() deepClone the incoming map before adopting it into config
// (matching core/api/alias-controller.ts:117's AliasController.updateFilters, which does the same),
// so a caller mutating its passed-in object after the call can't reach back into the report's
// persisted config through a shared reference.

import { NEVER, Observable, of, Subject } from 'rxjs';
import { AliasInfo, IAliasController } from '@core/api/widget-api.models';
import { Datasource, TargetDevice } from '@shared/models/widget.models';
import { EntityInfo } from '@app/shared/models/entity.models';
import { EntityAliases } from '@shared/models/alias.models';
import {
  Filter,
  FilterInfo,
  Filters,
  KeyFilter,
  keyFilterInfosToKeyFilters,
  keyFiltersToKeyFilterInfos
} from '@shared/models/query/query.models';
import { deepClone, isEqual } from '@core/utils';
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
    const cloned: EntityAliases = deepClone(entityAliases);
    this.getConfig().entityAliases = Object.keys(cloned).map((id) => ({
      id,
      alias: cloned[id].alias,
      filter: cloned[id].filter
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
        // Load-bearing, not cosmetic - see header "keyFilters CONVERSION" point 2.
        editable: false,
        keyFilters: keyFiltersToKeyFilterInfos(filter.keyFilters ?? [])
      };
    }
    return result;
  }

  updateFilters(filters: Filters): void {
    const changedFilterIds = diffChangedIds(this.getFilters(), filters);
    const cloned: Filters = deepClone(filters);
    this.getConfig().filters = Object.keys(cloned).map((id) => ({
      id,
      filter: cloned[id].filter,
      // Reverse of getFilters()'s conversion (header "keyFilters CONVERSION" note).
      keyFilters: keyFilterInfosToKeyFilters(cloned[id].keyFilters ?? [])
    }));
    if (changedFilterIds.length) {
      this.filtersChangedSubject.next(changedFilterIds);
    }
  }

  getFilterInfo(filterId: string): FilterInfo {
    return this.getFilters()[filterId];
  }

  getKeyFilters(filterId: string): Array<KeyFilter> {
    // Reads config.filters[] directly (NOT getFilterInfo()/getFilters()) - simpler than
    // filterInfoToKeyFilters(getFilterInfo(id)) and sidesteps that conversion's grouping/reorder
    // nuance (header "keyFilters CONVERSION" point 1). See header for the full rationale.
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
