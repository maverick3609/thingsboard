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
import { DialogComponent } from '@shared/components/dialog.component';
import { AppState } from '@core/core.state';
import { Device } from '@shared/models/device.models';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { DiscoveredController } from '@shared/models/inferrix-controller.models';

export interface AdoptControllerDialogData {
  controller?: DiscoveredController;
}

/**
 * Claims a controller and registers it as a device.
 *
 * The password field is the one part worth understanding. A factory controller has **no** password
 * and the first caller to provision it becomes its owner, so leaving the field blank is the normal
 * path — the platform generates one, keeps it sealed, and the operator never sees it. A controller
 * someone has already claimed (a bench commissioner, a previous install) refuses provisioning, and
 * because the firmware stores the password as PBKDF2 there is no way to recover it from the device.
 * That is when the operator has to supply the existing one.
 */
@Component({
  selector: 'tb-adopt-controller-dialog',
  templateUrl: './adopt-controller-dialog.component.html',
  standalone: false
})
export class AdoptControllerDialogComponent extends DialogComponent<AdoptControllerDialogComponent, Device> {

  adoptForm: UntypedFormGroup;
  readonly controller: DiscoveredController;
  errorMessage: string = null;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: AdoptControllerDialogData,
              public dialogRef: MatDialogRef<AdoptControllerDialogComponent, Device>,
              private fb: UntypedFormBuilder,
              private controllerService: InferrixControllerService) {
    super(store, router, dialogRef);
    this.controller = data?.controller;
    const identity = this.controller?.identity ?? {};
    this.adoptForm = this.fb.group({
      // Either the uid of something already seen announcing, or an address typed in by hand for a
      // controller being commissioned on a bench.
      host: [null, this.controller ? [] : [Validators.required]],
      name: [identity.name || null, [Validators.maxLength(31)]],
      location: [identity.location || null, [Validators.maxLength(63)]],
      label: [null, []],
      password: [null, []]
    });
  }

  adopt(): void {
    if (this.adoptForm.invalid) {
      this.adoptForm.markAllAsTouched();
      return;
    }
    this.errorMessage = null;
    const value = this.adoptForm.getRawValue();
    this.controllerService.adoptController({
      uid: this.controller?.uid,
      host: value.host || undefined,
      password: value.password || undefined,
      name: value.name || undefined,
      location: value.location || undefined,
      label: value.label || undefined
    }, {ignoreErrors: true}).subscribe({
      next: device => this.dialogRef.close(device),
      error: error => this.errorMessage = error?.error?.message || error?.message || 'Adoption failed'
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }
}
