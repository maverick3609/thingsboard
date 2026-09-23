// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, Optional, SkipSelf } from '@angular/core';
import { ErrorStateMatcher } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormGroupDirective, NgForm, UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { Device } from '@shared/models/device.models';
import { PendingGateway } from '@shared/models/inferrix-gateway.models';

/**
 * Brings a gateway under platform management.
 *
 * Cortex mints nothing here. The operator issues an API token on the gateway itself and pastes both
 * halves in — which is why the form asks for a `client_id` and a `client_secret` rather than
 * offering to generate a password. The platform seals them, and neither half comes back out of any
 * API afterwards.
 *
 * The address is a bare host or IP: no scheme, no port, no path. That is validated on the server
 * too, and it is a security control rather than tidiness — a host carrying a path composes into a
 * URL whose path is an endpoint the allowlist never approved.
 */
@Component({
  selector: 'tb-adopt-gateway-dialog',
  templateUrl: './adopt-gateway-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: AdoptGatewayDialogComponent}],
  styleUrls: ['./adopt-gateway-dialog.component.scss'],
  standalone: false
})
export class AdoptGatewayDialogComponent extends DialogComponent<AdoptGatewayDialogComponent, Device>
  implements ErrorStateMatcher {

  adoptForm: UntypedFormGroup;
  adopting = false;
  error: string;

  /** Shown only once the server has refused for this reason; never offered up front. */
  certificateChanged = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Optional() @Inject(MAT_DIALOG_DATA) public data: {pending?: PendingGateway},
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              public dialogRef: MatDialogRef<AdoptGatewayDialogComponent, Device>,
              private fb: UntypedFormBuilder,
              private gatewayService: InferrixGatewayService) {
    super(store, router, dialogRef);
    const pending = data?.pending;
    this.adoptForm = this.fb.group({
      // Prefilled from what the gateway published about itself, so the common path is a paste of
      // the token pair and nothing else. Still editable: the reported address is the device's own
      // claim, and the operator is the one who confirms it.
      deviceName: [pending?.name ?? '', [Validators.maxLength(255)]],
      address: [pending?.reportedAddress ?? '', [Validators.required, Validators.maxLength(255)]],
      port: [443, [Validators.min(1), Validators.max(65535)]],
      clientId: ['', [Validators.required]],
      clientSecret: ['', [Validators.required]]
    });
  }

  isErrorState(control: any, form: FormGroupDirective | NgForm | null): boolean {
    const originalErrorState = this.errorStateMatcher.isErrorState(control, form);
    const customErrorState = !!(control && control.invalid && control.touched);
    return originalErrorState || customErrorState;
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  adopt(): void {
    if (this.adoptForm.invalid) {
      this.adoptForm.markAllAsTouched();
      return;
    }
    this.adopting = true;
    this.error = null;
    this.gatewayService.adoptGateway({
      ...this.adoptForm.value,
      acceptDifferentGateway: this.certificateChanged || undefined
    }, {ignoreErrors: true}).subscribe({
      next: device => this.dialogRef.close(device),
      error: failure => {
        this.adopting = false;
        this.error = failure?.error?.message ?? failure?.message ?? '';
        // The server refuses a re-adoption whose certificate has changed, because that means
        // different hardware under a name whose dashboards and alarm rules refer to the old box.
        // Replacing a failed gateway is legitimate, so the operator is offered the confirmation —
        // but only after being told, and never as a checkbox they could tick out of habit.
        this.certificateChanged = this.error.includes('different certificate');
      }
    });
  }
}
