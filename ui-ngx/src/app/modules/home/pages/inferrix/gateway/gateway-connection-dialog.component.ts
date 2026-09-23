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
import { GatewayConnection } from '@shared/models/inferrix-gateway.models';

export interface GatewayConnectionDialogData {
  deviceId: string;
  connection: GatewayConnection;
}

/**
 * Changes where an adopted gateway is reached.
 *
 * A gateway lives on a LAN or a VPN and nowhere else, so its address belongs to the network rather
 * than to the box: subnets get renumbered, VPNs get replaced, cabinets get moved. None of that
 * changes the hardware, its certificate or its API token — which is why this form asks for an
 * address and nothing else. The platform spends the token it already holds to prove the new
 * address answers, so an operator who cannot read the sealed secret back can still do this.
 *
 * Same address rule as adoption, and for the same reason: a bare host or IP, no scheme, no port,
 * no path. A host carrying a path composes into a URL whose path is an endpoint the proxy's
 * allowlist never approved.
 */
@Component({
  selector: 'tb-gateway-connection-dialog',
  templateUrl: './gateway-connection-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: GatewayConnectionDialogComponent}],
  styleUrls: ['./gateway-connection-dialog.component.scss'],
  standalone: false
})
export class GatewayConnectionDialogComponent
  extends DialogComponent<GatewayConnectionDialogComponent, boolean> implements ErrorStateMatcher {

  connectionForm: UntypedFormGroup;
  saving = false;
  error: string;

  /** Shown only once the server has refused for this reason; never offered up front. */
  certificateChanged = false;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Optional() @Inject(MAT_DIALOG_DATA) public data: GatewayConnectionDialogData,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              public dialogRef: MatDialogRef<GatewayConnectionDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private gatewayService: InferrixGatewayService) {
    super(store, router, dialogRef);
    this.connectionForm = this.fb.group({
      address: [data?.connection?.address ?? '',
        [Validators.required, Validators.maxLength(255)]],
      // 443 for a gateway adopted before the port was recorded: it is the default the platform
      // falls back to anyway, so showing it is showing what is in force.
      port: [data?.connection?.port ?? 443, [Validators.min(1), Validators.max(65535)]]
    });
  }

  isErrorState(control: any, form: FormGroupDirective | NgForm | null): boolean {
    return this.errorStateMatcher.isErrorState(control, form)
      || !!(control && control.invalid && control.touched);
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  save(): void {
    if (this.connectionForm.invalid) {
      this.connectionForm.markAllAsTouched();
      return;
    }
    this.saving = true;
    this.error = null;
    this.gatewayService.changeConnection(this.data.deviceId, {
      ...this.connectionForm.value,
      acceptDifferentGateway: this.certificateChanged || undefined
    }, {ignoreErrors: true}).subscribe({
      next: () => this.dialogRef.close(true),
      error: failure => {
        this.saving = false;
        this.error = failure?.error?.message ?? failure?.message ?? '';
        // The server refuses an address whose certificate is not the pinned one, because that
        // means different hardware under a device whose dashboards and alarm rules refer to the
        // old box. Replacing a failed gateway is legitimate, so the operator is offered the
        // confirmation — but only after being told, and never as a box they could tick by habit.
        this.certificateChanged = this.error.includes('different certificate');
      }
    });
  }
}
