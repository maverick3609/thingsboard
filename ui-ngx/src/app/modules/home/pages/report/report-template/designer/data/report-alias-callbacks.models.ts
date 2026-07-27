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

// Pure types + DI token for Task 10's alias/filter dialog-callback factory. Deliberately ZERO import
// of EntityAliasDialogComponent/FilterDialogComponent (or anything that transitively reaches them) -
// this is exactly what lets report-entity-aliases.component.ts/report-filters.component.ts (and
// their specs) avoid the karma failure documented in report-alias-callbacks.ts's header: those two
// dialog classes are declared inside the large HomeComponentsModule (alongside WidgetConfigComponent),
// and Angular's Ivy compiler resolves that whole NgModule's declaration scope - including
// WidgetConfigComponent's widget-config.component.scss, which the karma builder's sass-loader can't
// resolve (a pre-existing, confirmed gap - see task-10-report.md) - the moment ANY file statically
// imports one of its declared classes AS A VALUE, even a bare, never-called import.
//
// REPORT_ALIAS_CALLBACKS_FACTORY is provided for real in report.module.ts's `providers` (the ONE
// place that imports the concrete createReportAliasCallbacks + dialog classes for production use,
// alongside MatDialog) - report.module.ts has no spec of its own, so nothing regresses. Component
// specs inject a fake factory function directly (a plain constructor param - no TestBed needed).
import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import { EntityAlias } from '@shared/models/alias.models';
import { EntityType } from '@shared/models/entity-type.models';
import { Filter } from '@shared/models/query/query.models';
import { IAliasController } from '@core/api/widget-api.models';

export interface ReportAliasCallbacks {
  createEntityAlias: (alias: string, allowedEntityTypes: Array<EntityType>) => Observable<EntityAlias>;
  editEntityAlias: (alias: EntityAlias, allowedEntityTypes: Array<EntityType>) => Observable<EntityAlias>;
  createFilter: (filter: string) => Observable<Filter>;
  editFilter: (filter: Filter) => Observable<Filter>;
}

export type ReportAliasCallbacksFactory = (aliasController: IAliasController) => ReportAliasCallbacks;

export const REPORT_ALIAS_CALLBACKS_FACTORY =
  new InjectionToken<ReportAliasCallbacksFactory>('REPORT_ALIAS_CALLBACKS_FACTORY');
