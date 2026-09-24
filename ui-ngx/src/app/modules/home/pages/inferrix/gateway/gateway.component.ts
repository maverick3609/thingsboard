// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, Inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { AttributeService } from '@core/http/attribute.service';
import { EntityComponent } from '@home/components/entity/entity.component';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { EntityType } from '@shared/models/entity-type.models';
import { AttributeScope } from '@shared/models/telemetry/telemetry.models';
import { GATEWAY_CONNECTION_KEYS, GATEWAY_REPORTED_KEYS, GatewayConnection, GatewayInfo,
  GatewayMqttConfiguration, gatewayConnectionOf } from '@shared/models/inferrix-gateway.models';
import { GatewayConnectionDialogComponent,
  GatewayConnectionDialogData } from '@home/pages/inferrix/gateway/gateway-connection-dialog.component';
import { GatewayBrokerDialogComponent,
  GatewayBrokerDialogData } from '@home/pages/inferrix/gateway/gateway-broker-dialog.component';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';

/**
 * The Details tab of an adopted gateway.
 *
 * Two halves, laid out as the controller's is. Above, the plain device record — name, label,
 * description — as the standard entity form. Below, what the platform recorded about how it
 * reaches this gateway: read from attributes, so it answers whether or not the gateway is up.
 * Whether it *is* up is the Health tab's question, and stays there.
 *
 * The connection is editable because the address belongs to the network rather than to the box: a
 * renumbered subnet or a new VPN moves a gateway that has not changed at all. It is not part of
 * the form above, though — saving the device record must not be able to repoint it, and the change
 * has to be proved against the new address before anything is written.
 *
 * Note what is still not here: the API token and the certificate fingerprint are not editable. The
 * token is sealed and never comes back out; the fingerprint is captured from whatever answers at
 * the address, and typing one in would be asserting a pin nobody checked.
 */
@Component({
  selector: 'tb-inferrix-gateway',
  templateUrl: './gateway.component.html',
  styleUrls: ['./gateway.component.scss'],
  standalone: false
})
export class GatewayComponent extends EntityComponent<GatewayInfo> {

  entityType = EntityType;

  connection: GatewayConnection;
  connectionLoading = false;

  /** Where the gateway dials this platform's broker, read from the gateway itself. */
  mqtt: GatewayMqttConfiguration;
  mqttLoading = false;

  /** The gateway the connection block currently describes. */
  private connectionDeviceId: string;

  constructor(protected store: Store<AppState>,
              @Inject('entity') protected entityValue: GatewayInfo,
              @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<GatewayInfo>,
              public fb: UntypedFormBuilder,
              protected cd: ChangeDetectorRef,
              private attributeService: AttributeService,
              private gatewayService: InferrixGatewayService,
              private dialog: MatDialog) {
    super(store, fb, entityValue, entitiesTableConfigValue, cd);
  }

  /** Changing where a gateway is reached is a write; a customer user may only look at it. */
  get readonly(): boolean {
    return !!this.entitiesTableConfig?.componentsData?.readonly;
  }

  buildForm(entity: GatewayInfo): UntypedFormGroup {
    return this.fb.group({
      name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255)]],
      label: [entity ? entity.label : '', [Validators.maxLength(255)]],
      additionalInfo: this.fb.group({
        description: [entity && entity.additionalInfo ? entity.additionalInfo.description : '']
      })
    });
  }

  updateForm(entity: GatewayInfo) {
    this.entityForm.patchValue({
      name: entity.name,
      label: entity.label,
      additionalInfo: {description: entity.additionalInfo ? entity.additionalInfo.description : ''}
    });
    // The details panel re-sets the entity every time edit mode is toggled, so only a different
    // gateway is worth another read.
    if (entity?.id?.id !== this.connectionDeviceId) {
      this.connectionDeviceId = entity?.id?.id;
      this.connection = null;
      this.mqtt = null;
      this.loadConnection();
      this.loadMqtt();
    }
  }

  hideDelete() {
    return this.entitiesTableConfig ? !this.entitiesTableConfig.deleteEnabled(this.entity) : false;
  }

  changeConnection(): void {
    this.dialog.open<GatewayConnectionDialogComponent, GatewayConnectionDialogData, boolean>(
      GatewayConnectionDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {deviceId: this.entity.id.id, connection: this.connection ?? {}}
      }).afterClosed().subscribe(changed => {
      if (changed) {
        this.loadConnection();
      }
    });
  }

  changeBroker(): void {
    this.dialog.open<GatewayBrokerDialogComponent, GatewayBrokerDialogData, boolean>(
      GatewayBrokerDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {deviceId: this.entity.id.id, configuration: this.mqtt ?? {}}
      }).afterClosed().subscribe(changed => {
      if (changed) {
        this.loadMqtt();
      }
    });
  }

  /**
   * A live read through the proxy, unlike the connection block above.
   *
   * There is no attribute mirroring the broker setting, and there should not be: the gateway is
   * the only thing that knows where it is dialling. So this fails whenever the gateway is
   * unreachable, and a failure is left as an empty panel rather than an error -- "the gateway is
   * down" is the connection block's answer, not this one's, and saying it twice in two different
   * words helps nobody.
   *
   * Refused outright for a customer user: the whole `/v2/platform-integration` family is
   * administrator-only in the proxy's allowlist. That is the same empty panel.
   */
  private loadMqtt(): void {
    if (!this.connectionDeviceId) {
      return;
    }
    this.mqttLoading = true;
    this.gatewayService.getMqttConfiguration(this.connectionDeviceId,
      {ignoreLoading: true, ignoreErrors: true}).subscribe({
      next: configuration => {
        this.mqtt = configuration;
        this.mqttLoading = false;
        this.cd.markForCheck();
      },
      error: () => {
        this.mqtt = null;
        this.mqttLoading = false;
        this.cd.markForCheck();
      }
    });
  }

  /**
   * Attributes rather than a probe: this is what the platform *believes*, which is the thing an
   * operator needs when the gateway has stopped answering at the address they are looking at.
   */
  private loadConnection(): void {
    if (!this.connectionDeviceId) {
      return;
    }
    this.connectionLoading = true;
    const read = (scope: AttributeScope, keys: string[]) =>
      this.attributeService.getEntityAttributes(this.entity.id, scope, keys,
        {ignoreLoading: true, ignoreErrors: true}).pipe(catchError(() => of([])));
    forkJoin([
      read(AttributeScope.SERVER_SCOPE, GATEWAY_CONNECTION_KEYS),
      read(AttributeScope.CLIENT_SCOPE, GATEWAY_REPORTED_KEYS)
    ]).subscribe(([recorded, reported]) => {
        this.connection = gatewayConnectionOf(recorded, reported);
        this.connectionLoading = false;
        // ignoreLoading means nothing else flips a flag this panel watches, and the details tabs
        // are OnPush: without this the block renders empty until something else redraws it.
        this.cd.markForCheck();
      });
  }
}
