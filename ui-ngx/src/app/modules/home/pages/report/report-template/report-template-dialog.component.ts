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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';

export interface ReportTemplateDialogData {
  reportTemplate?: Partial<ReportTemplate>;
  // false => edit an existing template's root fields. `format` and `type` are locked in that case:
  // both are structural (format decides the whole configuration shape, type decides whether the
  // template is standalone or embeddable), so flipping either on a template that already has a
  // built configuration would leave a tree the designer cannot render.
  isAdd?: boolean;
}

// The "create report template" step that PE puts in front of its designer: the four root fields
// (name/format/type/description) that are NOT part of the drag-and-drop configuration tree and that
// the designer therefore cannot ask for once it is open - `format` in particular decides which
// palette and which config shape the designer builds, so it has to be answered up front.
//
// Purely a form: it performs no HTTP of its own and closes with the seed object. The caller
// (report-templates-table-config.resolver.ts) hands that seed to the designer through router state;
// nothing is persisted until the user hits Save in the designer.
@Component({
  selector: 'tb-report-template-dialog',
  templateUrl: './report-template-dialog.component.html',
  standalone: false
})
export class ReportTemplateDialogComponent
  extends DialogComponent<ReportTemplateDialogComponent, Partial<ReportTemplate>> {

  reportTemplateFormGroup: FormGroup;
  isAdd: boolean;

  reportFormats = TbReportFormat;
  reportTemplateTypes = ReportTemplateType;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ReportTemplateDialogData,
              public dialogRef: MatDialogRef<ReportTemplateDialogComponent, Partial<ReportTemplate>>,
              private fb: FormBuilder) {
    super(store, router, dialogRef);
    const seed = data?.reportTemplate ?? {};
    this.isAdd = data?.isAdd !== false;
    this.reportTemplateFormGroup = this.fb.group({
      name: [seed.name ?? '', [Validators.required, Validators.maxLength(255)]],
      format: [seed.format ?? TbReportFormat.PDF, [Validators.required]],
      type: [seed.type ?? ReportTemplateType.REPORT, [Validators.required]],
      description: [seed.description ?? '']
    });
    if (!this.isAdd) {
      this.reportTemplateFormGroup.get('format').disable();
      this.reportTemplateFormGroup.get('type').disable();
    }
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  save(): void {
    if (this.reportTemplateFormGroup.invalid) {
      this.reportTemplateFormGroup.markAllAsTouched();
      return;
    }
    this.dialogRef.close(this.reportTemplateFormGroup.getRawValue());
  }
}
