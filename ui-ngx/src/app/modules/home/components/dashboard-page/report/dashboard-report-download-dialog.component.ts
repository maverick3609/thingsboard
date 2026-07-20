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
import { FormBuilder, FormGroup } from '@angular/forms';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { DashboardReportConfig } from '@shared/models/report.models';
import { Timewindow } from '@shared/models/time/time.models';

export interface DashboardReportDownloadDialogData {
  state?: string;
  timewindow?: Timewindow;
}

// Values accepted by DashboardReportConfig.type / RenderOptions.type
// (PlaywrightWebReportRenderer#render: matched case-insensitively against "pdf"/"png"/"jpeg",
// unset/unrecognized falls back to "pdf" - see application/.../report/render/PlaywrightWebReportRenderer.java).
const DASHBOARD_REPORT_TYPES: ReadonlyArray<string> = ['pdf', 'png', 'jpeg'];

// On-demand "download the dashboard I'm looking at right now" params dialog (task 25c), opened
// from DashboardPageComponent#downloadReport, which performs the actual download (via
// ReportService#downloadDashboardReport) once this dialog resolves with the chosen params.
// Declared in HomeComponentsModule (not ReportModule, which imports HomeComponentsModule for
// DashboardPageComponent et al. - declaring this dialog there instead would create a circular
// module dependency).
@Component({
  selector: 'tb-dashboard-report-download-dialog',
  templateUrl: './dashboard-report-download-dialog.component.html',
  standalone: false
})
export class DashboardReportDownloadDialogComponent
  extends DialogComponent<DashboardReportDownloadDialogComponent, Partial<DashboardReportConfig>> {

  reportTypes = DASHBOARD_REPORT_TYPES;

  downloadFormGroup: FormGroup;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: DashboardReportDownloadDialogData,
              public dialogRef: MatDialogRef<DashboardReportDownloadDialogComponent, Partial<DashboardReportConfig>>,
              private fb: FormBuilder) {
    super(store, router, dialogRef);
    this.downloadFormGroup = this.fb.group({
      type: ['pdf'],
      timezone: [''],
      timewindow: [data.timewindow || null],
      state: [data.state || '']
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  download(): void {
    if (this.downloadFormGroup.invalid) {
      return;
    }
    const formValue = this.downloadFormGroup.value;
    this.dialogRef.close({
      type: formValue.type,
      timezone: formValue.timezone || undefined,
      timewindow: formValue.timewindow || undefined,
      state: formValue.state || undefined
    });
  }
}
