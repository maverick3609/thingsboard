// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { DialogComponent } from '@shared/components/dialog.component';
import { AppState } from '@core/core.state';
import { EntityType } from '@shared/models/entity-type.models';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { DiscoveredController } from '@shared/models/inferrix-controller.models';

export interface AssignControllerDialogData {
  controller: DiscoveredController;
}

/**
 * Allocates an unadopted controller to a tenant. System administrator only.
 *
 * It is a separate step from adoption because ThingsBoard forbids a system administrator from
 * creating a device under a tenant — so the tenant's own administrator has to do the adopting, and
 * this is how they come to see the controller at all.
 */
@Component({
  selector: 'tb-assign-controller-dialog',
  templateUrl: './assign-controller-dialog.component.html',
  styleUrls: ['./assign-controller-dialog.component.scss'],
  standalone: false
})
export class AssignControllerDialogComponent
  extends DialogComponent<AssignControllerDialogComponent, DiscoveredController> {

  assignForm: UntypedFormGroup;
  entityType = EntityType;
  errorMessage: string = null;

  readonly controller: DiscoveredController;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: AssignControllerDialogData,
              public dialogRef: MatDialogRef<AssignControllerDialogComponent, DiscoveredController>,
              private fb: UntypedFormBuilder,
              private controllerService: InferrixControllerService) {
    super(store, router, dialogRef);
    this.controller = data.controller;
    this.assignForm = this.fb.group({
      tenantId: [null, [Validators.required]]
    });
  }

  assign(): void {
    if (this.assignForm.invalid) {
      this.assignForm.markAllAsTouched();
      return;
    }
    this.errorMessage = null;
    const tenantId = this.assignForm.get('tenantId').value;
    this.controllerService.assignController(this.controller.uid, tenantId.id ?? tenantId,
      {ignoreErrors: true}).subscribe({
      next: updated => this.dialogRef.close(updated),
      error: error => this.errorMessage = error?.error?.message || error?.message || 'Assignment failed'
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }
}
