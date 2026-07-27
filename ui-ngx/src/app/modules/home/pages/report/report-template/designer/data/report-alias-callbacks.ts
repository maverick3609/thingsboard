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

// Thin production wiring for Task 10's create/edit callbacks: supplies the REAL
// EntityAliasDialogComponent/FilterDialogComponent to report-alias-dialog-flow.ts's dialog-class-
// agnostic core (all the actual data-shape + keyFilters-conversion logic lives there).
//
// This file (and its two dialog-component imports) is deliberately NOT unit-tested directly via
// Karma: importing either dialog class here - even as a bare, unused value - forces Angular's Ivy
// compiler to resolve their declaring HomeComponentsModule's full compilation scope, which drags in
// WidgetConfigComponent's widget-config.component.scss. That scss fails to resolve under the karma
// builder's sass-loader specifically (`@use '@angular/material/core/tokens/m2-utils'` /
// `theme.scss` - a pre-existing gap, NOT introduced by this file: a full `ng build` succeeds, and a
// one-line `import {EntityAliasDialogComponent} from '...'; expect(...).toBeTruthy();` spec alone
// reproduces the identical karma failure - see task-10-report.md). What's NOT covered by Karma here
// is purely "does this thin wrapper pass the right class to the right helper", which is instead
// covered by the full-project AOT build (proves both imports resolve and the whole module compiles)
// and code review.
//
// Used exclusively by report.module.ts's REPORT_ALIAS_CALLBACKS_FACTORY provider
// (report-alias-callbacks.models.ts) - report-entity-aliases.component.ts/report-filters.component.ts
// depend on that provider token + the dialog-class-agnostic ReportAliasCallbacks interface only, so
// neither them nor their specs ever reach this file (or the dialog classes) either.
import { MatDialog } from '@angular/material/dialog';
import { IAliasController } from '@core/api/widget-api.models';
import { EntityAliasDialogComponent } from '@home/components/alias/entity-alias-dialog.component';
import { FilterDialogComponent } from '@home/components/filter/filter-dialog.component';
import {
  openEntityAliasDialog,
  openFilterDialog
} from '@home/pages/report/report-template/designer/data/report-alias-dialog-flow';
import { ReportAliasCallbacks } from '@home/pages/report/report-template/designer/data/report-alias-callbacks.models';

export function createReportAliasCallbacks(dialog: MatDialog, aliasController: IAliasController): ReportAliasCallbacks {
  return {
    createEntityAlias: (alias, allowedEntityTypes) =>
      openEntityAliasDialog(dialog, EntityAliasDialogComponent, aliasController, true,
        {id: null, alias, filter: {resolveMultiple: false}}, allowedEntityTypes),
    editEntityAlias: (alias, allowedEntityTypes) =>
      openEntityAliasDialog(dialog, EntityAliasDialogComponent, aliasController, false, alias, allowedEntityTypes),
    createFilter: (filter) =>
      openFilterDialog(dialog, FilterDialogComponent, aliasController, true,
        {id: null, filter, keyFilters: [], editable: true}),
    editFilter: (filter) =>
      openFilterDialog(dialog, FilterDialogComponent, aliasController, false, filter)
  };
}
