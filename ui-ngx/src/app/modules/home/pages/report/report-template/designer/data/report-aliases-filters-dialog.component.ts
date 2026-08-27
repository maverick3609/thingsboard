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

import { Component, Inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';

export interface ReportAliasesFiltersDialogData {
  config: ReportTemplateConfigModel;
  mode: 'aliases' | 'filters';
}

// The Aliases and Filters toolbar buttons PE puts on the report designer
// (docs/images/reporting-template-1..7.png). One dialog serves both because the two lists it hosts -
// tb-report-entity-aliases and tb-report-filters - already take the same single `config` Input and
// already own their own add/edit/delete flows; only the title and which one renders differ.
//
// Nothing is returned: both lists mutate config.entityAliases[]/config.filters[] in place through
// their own aliasController, so by the time this closes the edits are already in the config the
// designer holds. The caller decides whether that amounted to a change (see openAliasesFilters in
// report-template-editor.component.ts).
//
// Deliberately does NOT import EntityAliasDialogComponent/FilterDialogComponent, directly or
// transitively - those reach this file's module graph only through report.module.ts's
// REPORT_ALIAS_CALLBACKS_FACTORY provider. See report-alias-callbacks.ts's header for the karma
// build failure that indirection exists to avoid.
@Component({
  selector: 'tb-report-aliases-filters-dialog',
  templateUrl: './report-aliases-filters-dialog.component.html',
  standalone: false
})
export class ReportAliasesFiltersDialogComponent extends DialogComponent<ReportAliasesFiltersDialogComponent, void> {

  config: ReportTemplateConfigModel;
  mode: 'aliases' | 'filters';

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ReportAliasesFiltersDialogData,
              public dialogRef: MatDialogRef<ReportAliasesFiltersDialogComponent, void>) {
    super(store, router, dialogRef);
    this.config = data.config;
    this.mode = data.mode;
  }

  close(): void {
    this.dialogRef.close();
  }
}
