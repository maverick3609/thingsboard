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

// Template-level "manage aliases" list (Task 10, deliverable 2) - lists the report template's OWN
// config.entityAliases[], with add/edit/delete opening the platform's single-item
// EntityAliasDialogComponent (via the REPORT_ALIAS_CALLBACKS_FACTORY DI token, deliverable 1).
// Hosted by report-page-settings.component's "Manage aliases" entry point (an expansion panel),
// which is the only place that has a `config` reference to hand down (the shell owns `config`; the
// canvas/component-config panels own individual components, not the template-level
// entityAliases/filters).
//
// Depends on report-alias-callbacks.models.ts (interface + DI token) only - deliberately NOT on
// report-alias-callbacks.ts (the concrete factory, which imports
// EntityAliasDialogComponent/FilterDialogComponent). That indirection is load-bearing, not
// cosmetic: importing either dialog class ANYWHERE in this file's module graph - even as a bare,
// unused value - forces Angular's Ivy compiler to resolve their declaring HomeComponentsModule's
// full scope, which drags in WidgetConfigComponent's widget-config.component.scss and breaks the
// karma test build (confirmed via an isolated repro - see report-alias-callbacks.ts's header and
// task-10-report.md). Going through the injected factory keeps this component (and its spec, which
// supplies a fake factory) entirely clear of that - report.module.ts's REPORT_ALIAS_CALLBACKS_FACTORY
// provider is the one place that pulls in the real classes for production use.
//
// Every mutation (add/edit/delete) goes THROUGH a local aliasController (Task 9's
// createReportAliasController, built once here from this component's own `config` Input) rather than
// splicing config.entityAliases directly - even though entity aliases need no shape conversion
// (report EntityAlias.filter IS query.models.EntityFilter on both sides, see report-alias-controller
// .ts's header) - so both this component and report-filters.component.ts share one mutation path and
// one mental model, and so the platform dialogs always receive a correctly-typed platform
// EntityAliases map (report-alias-controller.spec.ts's getEntityAliases() test shows why: report
// EntityAlias's fields are optional, the platform's are not, so handing the raw report array to
// EntityAliasDialogData would be a structurally-unsound shape even though no VALUE conversion is
// needed). aliasController is a cheap, stateless, throwaway proxy over `config` (re-read fresh on
// every call, see that file's header) - there is no shared/live aliasController yet for this instance
// to diverge from (Task 11 wires a shell-level one for the datasource pickers).
//
// No "is this alias in use by a component's datasource" guard on delete (unlike the platform's
// EntityAliasesDialogComponent.removeAlias(), which blocks deletion of an alias referenced by a
// dashboard widget) - deliberately deferred: report-datasource's aliasController Input isn't wired to
// a live instance until Task 11, so "in use" isn't yet a fully meaningful question, and walking every
// component's dataSources[]/alarmSource (including nested SUB_REPORT) for a usage count is real
// complexity beyond this task's add/edit/delete brief. A user can currently delete an in-use alias
// and orphan the reference; flagged as a known gap, not silently fixed here.

import { Component, Inject, Input, OnInit } from '@angular/core';
import { EntityAlias } from '@shared/models/alias.models';
import { IAliasController } from '@core/api/widget-api.models';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { createReportAliasController } from '@home/pages/report/report-template/designer/data/report-alias-controller';
import {
  REPORT_ALIAS_CALLBACKS_FACTORY,
  ReportAliasCallbacks,
  ReportAliasCallbacksFactory
} from '@home/pages/report/report-template/designer/data/report-alias-callbacks.models';

@Component({
    selector: 'tb-report-entity-aliases',
    templateUrl: './report-entity-aliases.component.html',
    styleUrls: [],
    standalone: false
})
export class ReportEntityAliasesComponent implements OnInit {

  @Input()
  config: ReportTemplateConfigModel;

  aliases: EntityAlias[] = [];

  private aliasController: IAliasController;
  private callbacks: ReportAliasCallbacks;

  constructor(@Inject(REPORT_ALIAS_CALLBACKS_FACTORY) private callbacksFactory: ReportAliasCallbacksFactory) {
  }

  ngOnInit(): void {
    this.aliasController = createReportAliasController(() => this.config);
    this.callbacks = this.callbacksFactory(this.aliasController);
    this.refresh();
  }

  addAlias(): void {
    this.callbacks.createEntityAlias('', undefined).subscribe((alias) => {
      if (alias) {
        this.refresh();
      }
    });
  }

  editAlias(alias: EntityAlias): void {
    this.callbacks.editEntityAlias(alias, undefined).subscribe((result) => {
      if (result) {
        this.refresh();
      }
    });
  }

  deleteAlias(alias: EntityAlias): void {
    const aliases = this.aliasController.getEntityAliases();
    delete aliases[alias.id];
    this.aliasController.updateEntityAliases(aliases);
    this.refresh();
  }

  trackById(_index: number, alias: EntityAlias): string {
    return alias.id;
  }

  private refresh(): void {
    const aliases = this.aliasController.getEntityAliases();
    this.aliases = Object.keys(aliases).map(id => aliases[id]);
  }
}
