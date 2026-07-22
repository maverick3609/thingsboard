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

import { Component, Inject, SkipSelf } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormBuilder, FormControl, FormGroup, FormGroupDirective, NgForm, Validators } from '@angular/forms';
import { ErrorStateMatcher } from '@angular/material/core';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { EntityType } from '@shared/models/entity-type.models';
import { EntityId } from '@shared/models/id/entity-id';
import { deepClone } from '@core/utils';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { ReportService } from '@core/http/report.service';

export interface ReportTemplateDialogData {
  reportTemplate: ReportTemplate | null;
  isAdd: boolean;
}

// R1 dashboard-component-only editor: the `configuration` JSON is a raw passthrough
// (design spec ยง2.4) so on save we merge the form's edits into a deep clone of whatever
// configuration was loaded, rather than rebuilding it from scratch - this way any keys the R1
// form doesn't know about (future R2 component types/fields) survive an R1 edit untouched.
@Component({
  selector: 'tb-report-template-dialog',
  templateUrl: './report-template-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: ReportTemplateDialogComponent}],
  standalone: false
})
export class ReportTemplateDialogComponent extends DialogComponent<ReportTemplateDialogComponent, ReportTemplate>
  implements ErrorStateMatcher {

  reportTemplateFormGroup: FormGroup;
  isAdd: boolean;
  entityType = EntityType;
  submitted = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ReportTemplateDialogData,
              public dialogRef: MatDialogRef<ReportTemplateDialogComponent, ReportTemplate>,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              private fb: FormBuilder,
              private reportService: ReportService) {
    super(store, router, dialogRef);
    this.isAdd = data.isAdd;

    const template = data.reportTemplate;
    // PE shape (design spec §2.4 / R2a §4): {format: 'PDF', components: [{type: 'DASHBOARD',
    // config: {dashboardId, state, timewindow, ...}}]}. The DASHBOARD component is always
    // components[0] for the R1 single-component editor; its settings live under `config`.
    const component = template?.configuration?.components?.[0] || {};
    const componentConfig = component.config || {};
    const dashboardEntity: EntityId | null = componentConfig.dashboardId
      ? {entityType: EntityType.DASHBOARD, id: componentConfig.dashboardId}
      : null;

    this.reportTemplateFormGroup = this.fb.group({
      name: [template ? template.name : '', [Validators.required, Validators.maxLength(255)]],
      format: [{value: TbReportFormat.PDF, disabled: true}],
      type: [{value: ReportTemplateType.REPORT, disabled: true}],
      description: [template ? template.description : ''],
      dashboardComponent: this.fb.group({
        dashboardEntity: [dashboardEntity, [Validators.required]],
        state: [componentConfig.state || ''],
        timewindow: [componentConfig.timewindow || null]
      })
    });
  }

  isErrorState(control: FormControl | null, form: FormGroupDirective | NgForm | null): boolean {
    const originalErrorState = this.errorStateMatcher.isErrorState(control, form);
    const customErrorState = !!(control && control.invalid && this.submitted);
    return originalErrorState || customErrorState;
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  save(): void {
    this.submitted = true;
    if (this.reportTemplateFormGroup.invalid) {
      return;
    }
    const formValue = this.reportTemplateFormGroup.getRawValue();
    const dashboardComponent = formValue.dashboardComponent;

    // Merge, don't rebuild: start from a deep clone of the loaded configuration (or the minimal
    // PE-shape skeleton for a NEW template) and set ONLY the fields this form edits, leaving every
    // other existing top-level/component key intact. Per Decision D1 the wire shape is PE-nested:
    // root keyed on `format`, the DASHBOARD component nests its settings under `config`.
    const configuration = this.data.reportTemplate?.configuration
      ? deepClone(this.data.reportTemplate.configuration)
      : {format: TbReportFormat.PDF, components: [{type: 'DASHBOARD', config: {}}]};
    if (!Array.isArray(configuration.components) || !configuration.components.length) {
      configuration.components = [{type: 'DASHBOARD', config: {}}];
    }
    configuration.format = TbReportFormat.PDF;
    const dashboardComponentConfig = configuration.components[0];
    dashboardComponentConfig.type = 'DASHBOARD';
    if (!dashboardComponentConfig.config) {
      dashboardComponentConfig.config = {};
    }
    dashboardComponentConfig.config.dashboardId = dashboardComponent.dashboardEntity?.id;
    dashboardComponentConfig.config.state = dashboardComponent.state;
    dashboardComponentConfig.config.timewindow = dashboardComponent.timewindow;

    const reportTemplate: ReportTemplate = {
      ...(this.data.reportTemplate || {} as ReportTemplate),
      name: formValue.name,
      format: TbReportFormat.PDF,
      type: ReportTemplateType.REPORT,
      description: formValue.description,
      configuration
    };
    this.reportService.saveReportTemplate(reportTemplate).subscribe({
      next: saved => this.dialogRef.close(saved),
      error: err => console.error('[Reporting] save report template failed', err)
    });
  }
}
