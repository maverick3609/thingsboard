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

// Dialog-class-AGNOSTIC data-flow core for Task 10's create/edit callbacks (see
// report-alias-callbacks.models.ts's header for why this split from report-alias-callbacks.ts
// exists). `dialogComponent` is a plain parameter here, never a concrete import -
// report-alias-callbacks.ts (production) calls these with the real
// EntityAliasDialogComponent/FilterDialogComponent; report-alias-callbacks.spec.ts (tests) calls them
// with a throwaway placeholder class, exercising the IDENTICAL data-shape-construction and
// keyFilters-conversion logic without ever needing webpack to resolve the real dialogs' declaring
// NgModule (HomeComponentsModule, which drags in WidgetConfigComponent's
// widget-config.component.scss - broken under the karma builder specifically; confirmed via an
// isolated one-line repro documented in task-10-report.md).
//
// Mirrors widget-config.component.ts:840-902's createEntityAlias/editEntityAlias/createFilter shape
// (disableClose/panelClass/afterClosed -> merge into the map -> write back), swapping
// `this.dashboard.configuration.entityAliases/filters` for the Task 9 aliasController shim
// (report-alias-controller.ts) - see that file's header for the keyFilters
// KeyFilterInfo[]<->KeyFilter[] reshape getFilters()/updateFilters() perform.
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { deepClone } from '@core/utils';
import { IAliasController } from '@core/api/widget-api.models';
import { EntityAlias } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { Filter } from '@shared/models/query/query.models';

const DIALOG_PANEL_CONFIG = {disableClose: true, panelClass: ['tb-dialog', 'tb-fullscreen-dialog']};

export function openEntityAliasDialog(dialog: MatDialog, dialogComponent: any, aliasController: IAliasController,
                                       isAdd: boolean, alias: EntityAlias,
                                       allowedEntityTypes: Array<EntityType>): Observable<EntityAlias> {
  return dialog.open(dialogComponent, {
    ...DIALOG_PANEL_CONFIG,
    data: {
      isAdd,
      allowedEntityTypes,
      entityAliases: aliasController.getEntityAliases(),
      alias: isAdd ? alias : deepClone(alias)
    }
  }).afterClosed().pipe(
    tap((entityAlias: EntityAlias) => {
      if (entityAlias) {
        const aliases = aliasController.getEntityAliases();
        aliases[entityAlias.id] = entityAlias;
        aliasController.updateEntityAliases(aliases);
      }
    })
  );
}

export function openFilterDialog(dialog: MatDialog, dialogComponent: any, aliasController: IAliasController,
                                  isAdd: boolean, filter: Filter): Observable<Filter> {
  return dialog.open(dialogComponent, {
    ...DIALOG_PANEL_CONFIG,
    data: {
      isAdd,
      filters: aliasController.getFilters(),
      filter: isAdd ? filter : deepClone(filter)
    }
  }).afterClosed().pipe(
    tap((result: Filter) => {
      if (result) {
        const filters = aliasController.getFilters();
        filters[result.id] = result;
        aliasController.updateFilters(filters);
      }
    })
  );
}
