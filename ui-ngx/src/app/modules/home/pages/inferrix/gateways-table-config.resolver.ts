// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import { select, Store } from '@ngrx/store';
import { Observable } from 'rxjs';
import { map, take, tap } from 'rxjs/operators';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { AppState } from '@core/core.state';
import { selectAuthUser } from '@core/auth/auth.selectors';
import { DeviceService } from '@core/http/device.service';
import { Device, DeviceInfoQuery } from '@shared/models/device.models';
import {
  GATEWAY_COLUMN_VALUES,
  GatewayInfo,
  gatewayTableAccess
} from '@shared/models/inferrix-gateway.models';
import { GatewayComponent } from '@home/pages/inferrix/gateway/gateway.component';
import { GatewayTabsComponent } from '@home/pages/inferrix/gateway/gateway-tabs.component';
import { AdoptGatewayDialogComponent } from '@home/pages/inferrix/adopt-gateway-dialog.component';

/**
 * The adopted gateway fleet, as a standard entities table.
 *
 * A gateway is a real ThingsBoard device, so this is the device table with the gateway profile
 * filtered in. Reusing the platform's own table brings search, paging, sorting, delete and the
 * details drawer with it — and it replaces ThingsBoard's stock Gateways page, so looking like every
 * other list in the product is the point rather than a nicety.
 */
@Injectable()
export class GatewaysTableConfigResolver {

  private readonly config: EntityTableConfig<GatewayInfo> = new EntityTableConfig<GatewayInfo>();

  constructor(private store: Store<AppState>,
              private deviceService: DeviceService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private router: Router) {

    this.config.entityType = EntityType.DEVICE;
    this.config.entityComponent = GatewayComponent;
    this.config.entityTabsComponent = GatewayTabsComponent;
    // The rows are devices, but the section is Gateways: taking the device translations wholesale
    // labels the toolbar "Add device" and the empty state "No devices found".
    this.config.entityTranslations = {
      ...entityTypeTranslations.get(EntityType.DEVICE),
      type: 'inferrix.gateway.gateway',
      typePlural: 'inferrix.gateway.gateways',
      details: 'inferrix.gateway.details',
      add: 'inferrix.gateway.adopt',
      noEntities: 'inferrix.gateway.none',
      search: 'inferrix.gateway.search',
      selectedEntities: 'inferrix.gateway.selected'
    };
    this.config.entityResources = entityTypeResources.get(EntityType.DEVICE);
    this.config.tableTitle = this.translate.instant('inferrix.gateway.gateways');
    this.config.entityTitle = gateway => gateway ? gateway.name : '';

    // A gateway is hardware that provisioned itself, not something typed into a form, so the add
    // action opens adoption.
    this.config.addEntity = () => this.openAdoptDialog();
    this.config.deleteEntityTitle = gateway =>
      this.translate.instant('inferrix.gateway.delete-title', {gatewayName: gateway.name});
    this.config.deleteEntityContent = () => this.translate.instant('inferrix.gateway.delete-text');
    this.config.deleteEntitiesTitle = count =>
      this.translate.instant('inferrix.gateway.delete-many-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('inferrix.gateway.delete-many-text');
    this.config.deleteEntity = id => this.deviceService.deleteDevice(id.id);
    this.config.loadEntity = id =>
      this.deviceService.getDeviceInfo(id.id) as unknown as Observable<GatewayInfo>;
    this.config.onEntityAction = action => this.onGatewayAction(action);
    // saveDevice returns a Device; the extra GatewayInfo fields are read-only live data the table
    // re-fetches, so widening here would claim they came back from the save.
    this.config.saveEntity = gateway =>
      this.deviceService.saveDevice(gateway) as unknown as Observable<GatewayInfo>;

    this.config.columns.push(
      new DateEntityTableColumn<GatewayInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      // Every device-reported column goes through the shared escaper. The table renders cells with
      // bypassSecurityTrustHtml and a gateway publishes its own address over MQTT, so an unescaped
      // column here is script in an operator's browser.
      new EntityTableColumn<GatewayInfo>('name', 'device.name', '30%',
        GATEWAY_COLUMN_VALUES.name, () => ({}), false),
      new EntityTableColumn<GatewayInfo>('label', 'device.label', '25%',
        GATEWAY_COLUMN_VALUES.label, () => ({}), false),
      new EntityTableColumn<GatewayInfo>('active', 'device.state', '80px',
        gateway => this.gatewayState(gateway), gateway => this.gatewayStateStyle(gateway), false)
    );
  }

  /**
   * A customer user has no access to the tenant-wide device listing, so the filter carries their
   * customer id and ThingsBoard's own query object picks the right URL. The branch itself lives in
   * the models layer, where it can be tested — a spec on this file drags in the DialogService
   * circular import that crashes the karma bundle.
   */
  resolve(): Observable<EntityTableConfig<GatewayInfo>> {
    return this.store.pipe(select(selectAuthUser), take(1)).pipe(
      tap(authUser => {
        const access = gatewayTableAccess(authUser.authority, authUser.customerId);
        this.config.componentsData = {readonly: access.readonly};
        this.config.addEnabled = access.canAdopt;
        this.config.entitiesDeleteEnabled = access.canAdopt;
        this.config.deleteEnabled = () => access.canAdopt;
        this.config.detailsReadonly = () => access.readonly;
        this.config.headerActionDescriptors.length = 0;
        if (access.canAdopt) {
          // Pending is a tenant-admin route; a customer user must not be offered a button into a 403.
          this.config.headerActionDescriptors.push({
            name: this.translate.instant('inferrix.gateway.pending'),
            icon: 'pending_actions',
            isEnabled: () => true,
            onAction: () => this.router.navigateByUrl('/entities/gateways/pending')
          });
        }
        this.config.entitiesFetchFunction = pageLink =>
          this.deviceService.getDeviceInfosByQuery(new DeviceInfoQuery(pageLink, access.filter)) as any;
      }),
      map(() => this.config)
    );
  }

  /**
   * Adoption returns the plain Device it created. The table's own refresh is what fills in the
   * DeviceInfo columns, so the cast says "the table will resolve this", not "these fields are here".
   */
  private openAdoptDialog(): Observable<GatewayInfo> {
    return this.dialog.open<AdoptGatewayDialogComponent, any, Device>(
      AdoptGatewayDialogComponent, {disableClose: true, panelClass: ['tb-dialog', 'tb-fullscreen-dialog']})
      .afterClosed() as unknown as Observable<GatewayInfo>;
  }

  /**
   * 'delete' is handled by the table itself; only the jump from the details drawer to the full page
   * needs an owner, and it is the same relative navigation the device table does.
   */
  private onGatewayAction(action: EntityAction<GatewayInfo>): boolean {
    if (action.action === 'open') {
      action.event?.stopPropagation();
      this.router.navigateByUrl(
        this.router.createUrlTree([action.entity.id.id], {relativeTo: this.config.getActivatedRoute()}));
      return true;
    }
    return false;
  }

  /** The same pill the device table uses, so a gateway reads the way every other device does. */
  private gatewayState(gateway: GatewayInfo): string {
    const translateKey = gateway.active ? 'device.active' : 'device.inactive';
    const backgroundColor = gateway.active ? 'rgba(25, 128, 56, 0.08)' : 'rgba(209, 39, 48, 0.08)';
    return `<div class="status" style="border-radius: 16px; height: 32px;
                line-height: 32px; padding: 0 12px; width: fit-content; background-color: ${backgroundColor}">
                ${this.translate.instant(translateKey)}
            </div>`;
  }

  private gatewayStateStyle(gateway: GatewayInfo): object {
    return {fontSize: '14px', color: gateway.active ? '#198038' : '#d12730'};
  }
}
