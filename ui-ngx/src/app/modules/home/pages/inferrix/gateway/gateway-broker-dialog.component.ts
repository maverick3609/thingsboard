// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject, Optional, SkipSelf } from '@angular/core';
import { ErrorStateMatcher } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { FormGroupDirective, NgForm, UntypedFormBuilder, UntypedFormGroup,
  Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayMqttConfiguration } from '@shared/models/inferrix-gateway.models';

export interface GatewayBrokerDialogData {
  deviceId: string;
  configuration: GatewayMqttConfiguration;
}

/**
 * The four schemes Paho installs a network module for: `tcp`, `ssl`, `ws`, `wss`.
 *
 * Checked here because the gateway does not check it. A URI with any other scheme is stored
 * happily and then fails inside the client on the reconnect the save triggers — so the first sign
 * of the typo is a gateway that has gone quiet, with the old address already overwritten.
 */
const BROKER_URI = /^(tcp|ssl|ws|wss):\/\/[^\s/]+(\/\S*)?$/;

/**
 * Only ever used when a read answered without one, which a healthy gateway never does.
 *
 * It is the gateway's own default and what its running client is pinned to regardless of what is
 * stored, so it is the safe thing to send. The value has to be *some* member of the gateway's
 * `QosType`: the save resolves it with `valueOf`, so an omitted or invented one fails the write.
 */
const DEFAULT_QOS = 'ATLEAST_ONCE';

/**
 * Where the gateway dials this platform's MQTT broker.
 *
 * The mirror of {@link GatewayConnectionDialogComponent}: that one changes where the platform
 * reaches the gateway, this one changes where the gateway reaches the platform. Both move for the
 * same reason — the address belongs to the network, not to either box — and a platform that has
 * been renumbered breaks this half while leaving the other half working, which is why it needs
 * its own form rather than a note telling the operator to log in to the gateway.
 *
 * **What this form deliberately does not edit: `clientId`, `userName` and `userPassword`.** On
 * ThingsBoard those three *are* the gateway's device credential — an adopted gateway here carries
 * `MQTT_BASIC` credentials whose stored `clientId` is this exact string — so changing one from
 * this side would leave the device record naming the old value and the broker refusing the
 * connect. They are carried through the save untouched, along with the TLS material, which has no
 * form here yet because every deployment so far is a plain `tcp://` broker on the same network.
 *
 * A save reconnects the gateway's MQTT client within seconds and needs no stack restart: the
 * gateway watches this settings row and rebuilds the client against it. That cuts both ways, and
 * the dialog says so — a wrong address takes telemetry down just as promptly.
 */
@Component({
  selector: 'tb-gateway-broker-dialog',
  templateUrl: './gateway-broker-dialog.component.html',
  providers: [{provide: ErrorStateMatcher, useExisting: GatewayBrokerDialogComponent}],
  styleUrls: ['./gateway-broker-dialog.component.scss'],
  standalone: false
})
export class GatewayBrokerDialogComponent
  extends DialogComponent<GatewayBrokerDialogComponent, boolean> implements ErrorStateMatcher {

  brokerForm: UntypedFormGroup;
  saving = false;
  error: string;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Optional() @Inject(MAT_DIALOG_DATA) public data: GatewayBrokerDialogData,
              @SkipSelf() private errorStateMatcher: ErrorStateMatcher,
              public dialogRef: MatDialogRef<GatewayBrokerDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private gatewayService: InferrixGatewayService) {
    super(store, router, dialogRef);
    const configuration = data?.configuration ?? {};
    this.brokerForm = this.fb.group({
      brokerUri: [configuration.brokerUri ?? '',
        [Validators.required, Validators.maxLength(255), Validators.pattern(BROKER_URI)]],
      topicFilters: [configuration.topicFilters ?? ''],
      keepAliveInterval: [configuration.keepAliveInterval ?? 60,
        [Validators.min(0), Validators.max(65535)]],
      connectionTimeout: [configuration.connectionTimeout ?? 30,
        [Validators.min(1), Validators.max(3600)]],
      autoReconnect: [configuration.autoReconnect ?? true],
      cleanSession: [configuration.cleanSession ?? true]
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
    if (this.brokerForm.invalid) {
      this.brokerForm.markAllAsTouched();
      return;
    }
    this.saving = true;
    this.error = null;
    // Spread the configuration that was read, so the credential, the TLS material and `qosType`
    // survive. `qosType` is not optional on the way in even though nothing here offers it: the
    // gateway resolves it with `QosType.valueOf`, which throws on a missing value -- hence the
    // fallback below, which a real read never needs. The two write-only secrets are absent from
    // the read and stay absent from the write -- the gateway carries its stored ones forward for
    // exactly those.
    const configuration: GatewayMqttConfiguration = {
      ...this.data.configuration, ...this.brokerForm.value,
      // Not a spread default underneath: a spread copies a key that is present and null, so the
      // default would be clobbered by exactly the value it exists to replace.
      qosType: this.data.configuration?.qosType || DEFAULT_QOS
    };
    this.gatewayService.saveMqttConfiguration(this.data.deviceId, configuration,
      {ignoreErrors: true}).subscribe({
      next: () => this.dialogRef.close(true),
      error: failure => {
        this.saving = false;
        this.error = failure?.error?.message ?? failure?.message ?? '';
      }
    });
  }
}
