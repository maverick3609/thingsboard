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

// Dialog-callback factory for the report designer (Task 10). Produces the exact function shapes
// tb-entity-alias-select / tb-filter-select (settings/common/alias|filter/*) consume through their
// own `callbacks: EntityAliasSelectCallbacks` / `callbacks: FilterSelectCallbacks` @Input()s (see
// entity-alias-select.component.ts's createEntityAlias()/editEntityAlias() and
// filter-select.component.ts's createFilter() - both no-op when `callbacks` is absent, so this is
// safe to wire wherever those two pickers appear). Mirrors widget-config.component.ts:840-902's
// createEntityAlias/editEntityAlias/createFilter verbatim (same dialogs, same disableClose/
// panelClass, same "afterClosed -> merge into the map -> write back" shape), with ONE swap: the
// dashboard version reads/writes `this.dashboard.configuration.entityAliases/filters` (already
// platform-shaped maps) directly, whereas here there is no live dashboard - the report template's
// `entityAliases[]`/`filters[]` are plain REPORT-shaped arrays (report-configuration.models.ts), so
// every read/write instead goes THROUGH Task 9's aliasController shim (report-alias-controller.ts):
// getEntityAliases()/getFilters() hand back correctly-typed platform maps (EntityAliases/Filters,
// query.models.ts) for the dialogs to consume, and updateEntityAliases()/updateFilters() convert
// back and persist into config.entityAliases[]/config.filters[] - including the filter keyFilters
// KeyFilterInfo[]<->KeyFilter[] reshape (see that file's header) - so this module never touches the
// conversion itself.
//
// editFilter() has no counterpart in FilterSelectCallbacks (the picker widget never edits an
// existing filter, only creates one inline) - added here anyway because Task 10's own "manage
// filters" list (report-filters.component.ts) needs a per-row Edit action and this is the same
// dialog-open-then-write-back shape as the other three, so it lives alongside them rather than
// duplicated in the component.

import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { deepClone } from '@core/utils';
import { IAliasController } from '@core/api/widget-api.models';
import { EntityAlias } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { Filter } from '@shared/models/query/query.models';
import { EntityAliasDialogComponent, EntityAliasDialogData } from '@home/components/alias/entity-alias-dialog.component';
import { FilterDialogComponent, FilterDialogData } from '@home/components/filter/filter-dialog.component';
import {
  EntityAliasSelectCallbacks
} from '@home/components/widget/lib/settings/common/alias/entity-alias-select.component.models';
import { FilterSelectCallbacks } from '@home/components/widget/lib/settings/common/filter/filter-select.component.models';

export interface ReportAliasCallbacks extends EntityAliasSelectCallbacks, FilterSelectCallbacks {
  editFilter: (filter: Filter) => Observable<Filter>;
}

export function createReportAliasCallbacks(dialog: MatDialog, aliasController: IAliasController): ReportAliasCallbacks {
  return {
    createEntityAlias: (alias, allowedEntityTypes) => createEntityAlias(dialog, aliasController, alias, allowedEntityTypes),
    editEntityAlias: (alias, allowedEntityTypes) => editEntityAlias(dialog, aliasController, alias, allowedEntityTypes),
    createFilter: (filter) => createFilter(dialog, aliasController, filter),
    editFilter: (filter) => editFilter(dialog, aliasController, filter)
  };
}

function createEntityAlias(dialog: MatDialog, aliasController: IAliasController, alias: string,
                            allowedEntityTypes: Array<EntityType>): Observable<EntityAlias> {
  const singleEntityAlias: EntityAlias = {id: null, alias, filter: {resolveMultiple: false}};
  return dialog.open<EntityAliasDialogComponent, EntityAliasDialogData, EntityAlias>(EntityAliasDialogComponent, {
    disableClose: true,
    panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
    data: {
      isAdd: true,
      allowedEntityTypes,
      entityAliases: aliasController.getEntityAliases(),
      alias: singleEntityAlias
    }
  }).afterClosed().pipe(
    tap((entityAlias) => {
      if (entityAlias) {
        const aliases = aliasController.getEntityAliases();
        aliases[entityAlias.id] = entityAlias;
        aliasController.updateEntityAliases(aliases);
      }
    })
  );
}

function editEntityAlias(dialog: MatDialog, aliasController: IAliasController, alias: EntityAlias,
                          allowedEntityTypes: Array<EntityType>): Observable<EntityAlias> {
  return dialog.open<EntityAliasDialogComponent, EntityAliasDialogData, EntityAlias>(EntityAliasDialogComponent, {
    disableClose: true,
    panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
    data: {
      isAdd: false,
      allowedEntityTypes,
      entityAliases: aliasController.getEntityAliases(),
      alias: deepClone(alias)
    }
  }).afterClosed().pipe(
    tap((entityAlias) => {
      if (entityAlias) {
        const aliases = aliasController.getEntityAliases();
        aliases[entityAlias.id] = entityAlias;
        aliasController.updateEntityAliases(aliases);
      }
    })
  );
}

function createFilter(dialog: MatDialog, aliasController: IAliasController, filter: string): Observable<Filter> {
  const singleFilter: Filter = {id: null, filter, keyFilters: [], editable: true};
  return dialog.open<FilterDialogComponent, FilterDialogData, Filter>(FilterDialogComponent, {
    disableClose: true,
    panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
    data: {
      isAdd: true,
      filters: aliasController.getFilters(),
      filter: singleFilter
    }
  }).afterClosed().pipe(
    tap((result) => {
      if (result) {
        const filters = aliasController.getFilters();
        filters[result.id] = result;
        aliasController.updateFilters(filters);
      }
    })
  );
}

function editFilter(dialog: MatDialog, aliasController: IAliasController, filter: Filter): Observable<Filter> {
  return dialog.open<FilterDialogComponent, FilterDialogData, Filter>(FilterDialogComponent, {
    disableClose: true,
    panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
    data: {
      isAdd: false,
      filters: aliasController.getFilters(),
      filter: deepClone(filter)
    }
  }).afterClosed().pipe(
    tap((result) => {
      if (result) {
        const filters = aliasController.getFilters();
        filters[result.id] = result;
        aliasController.updateFilters(filters);
      }
    })
  );
}
