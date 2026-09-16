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
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerPoint, controllerRecordError } from '@shared/models/inferrix-controller.models';

export interface ControllerPointWriteDialogData {
  deviceId: string;
  point: ControllerPoint;
}

/**
 * Forces one value onto a writable point.
 *
 * The input follows the value the point already reports rather than its source class: a Modbus coil
 * is an `rtu` point that reads as a bool, and a local output can be either.
 */
@Component({
  selector: 'tb-controller-point-write-dialog',
  templateUrl: './controller-point-write-dialog.component.html',
  styleUrls: ['./controller-point-write-dialog.component.scss'],
  standalone: false
})
export class ControllerPointWriteDialogComponent
  extends DialogComponent<ControllerPointWriteDialogComponent, boolean> {

  readonly point: ControllerPoint;
  readonly isBoolean: boolean;

  writeForm: UntypedFormGroup;
  errorMessage: string = null;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ControllerPointWriteDialogData,
              public dialogRef: MatDialogRef<ControllerPointWriteDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private translate: TranslateService,
              private controllerService: InferrixControllerService) {
    super(store, router, dialogRef);
    this.point = data.point;
    this.isBoolean = typeof data.point.v === 'boolean';
    this.writeForm = this.fb.group({
      value: [this.isBoolean ? data.point.v : null, this.isBoolean ? [] : [Validators.required]]
    });
  }

  write(): void {
    if (this.writeForm.invalid) {
      this.writeForm.markAllAsTouched();
      return;
    }
    this.errorMessage = null;
    const value = this.writeForm.get('value').value;
    this.controllerService.writePoint(this.data.deviceId, this.point,
      this.isBoolean ? !!value : Number(value)).subscribe({
      next: () => this.dialogRef.close(true),
      error: error => this.errorMessage = error?.error?.message
        || controllerRecordError(error, this.translate.instant('inferrix.point-write-failed'))
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }
}
