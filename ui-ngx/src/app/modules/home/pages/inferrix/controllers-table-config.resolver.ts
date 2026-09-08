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

import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import { select, Store } from '@ngrx/store';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError, map, mergeMap, take, tap } from 'rxjs/operators';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { EntityAction } from '@home/models/entity/entity-component.models';
import { PageData } from '@shared/models/page/page-data';
import { AppState } from '@core/core.state';
import { selectAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { DeviceService } from '@core/http/device.service';
import { AttributeService } from '@core/http/attribute.service';
import { Device, DeviceInfo, DeviceInfoFilter, DeviceInfoQuery } from '@shared/models/device.models';
import { CustomerId } from '@shared/models/id/customer-id';
import {
  CONTROLLER_ATTRIBUTE_KEYS,
  ControllerInfo,
  escapeCell,
  INFERRIX_CONTROLLER_PROFILE
} from '@shared/models/inferrix-controller.models';
import { ControllerComponent } from '@home/pages/inferrix/controller/controller.component';
import { ControllerTabsComponent } from '@home/pages/inferrix/controller/controller-tabs.component';
import { AdoptControllerDialogComponent } from '@home/pages/inferrix/adopt-controller-dialog.component';

/**
 * The adopted controller fleet, as a standard entities table.
 *
 * A controller is a real ThingsBoard device, so this is the device table with the Inferrix profile
 * filtered in and the device's self-reported identity added as columns. Reusing the platform's own
 * table brings search, paging, sorting, selection, delete and the details drawer with it, and keeps
 * the section looking like every other list in the product.
 */
@Injectable()
export class ControllersTableConfigResolver {

  private readonly config: EntityTableConfig<ControllerInfo> = new EntityTableConfig<ControllerInfo>();

  constructor(private store: Store<AppState>,
              private deviceService: DeviceService,
              private attributeService: AttributeService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private router: Router) {

    this.config.entityType = EntityType.DEVICE;
    this.config.entityComponent = ControllerComponent;
    this.config.entityTabsComponent = ControllerTabsComponent;
    // The rows are devices, but the section is Controllers: taking the device translations wholesale
    // labelled the toolbar "Add device" and the empty state "No devices found".
    this.config.entityTranslations = {
      ...entityTypeTranslations.get(EntityType.DEVICE),
      type: 'inferrix.controller',
      typePlural: 'inferrix.controllers',
      details: 'inferrix.controller-details',
      add: 'inferrix.adopt-controller',
      noEntities: 'inferrix.no-controllers',
      search: 'inferrix.search-controllers',
      selectedEntities: 'inferrix.selected-controllers'
    };
    this.config.entityResources = entityTypeResources.get(EntityType.DEVICE);
    this.config.tableTitle = this.translate.instant('inferrix.controllers');
    this.config.entityTitle = controller => controller ? controller.name : '';

    // A controller is claimed hardware, not something typed into a form, so the add action opens
    // adoption. Everything else is ordinary device behaviour.
    this.config.addEntity = () => this.openAdoptDialog();
    this.config.deleteEntityTitle = controller =>
      this.translate.instant('inferrix.delete-controller-title', {controllerName: controller.name});
    this.config.deleteEntityContent = () => this.translate.instant('inferrix.delete-controller-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('inferrix.delete-controllers-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('inferrix.delete-controllers-text');
    this.config.deleteEntity = id => this.deviceService.deleteDevice(id.id);
    this.config.loadEntity = id => this.loadController(id.id);
    this.config.onEntityAction = action => this.onControllerAction(action);
    this.config.saveEntity = controller => this.saveController(controller);

    // Discovery is a tenant-admin route; a customer user must not be offered a button into a 403.
    this.config.headerActionDescriptors.push({
      name: this.translate.instant('inferrix.discovered-controllers'),
      icon: 'wifi_tethering',
      isEnabled: () => !this.config.componentsData?.readonly,
      onAction: () => this.router.navigateByUrl('/controllers/discovered')
    });

    this.config.columns.push(
      new DateEntityTableColumn<ControllerInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      // Escaped like the rest: a controller's device name is seeded from the name the device
      // itself announced at adoption.
      new EntityTableColumn<ControllerInfo>('name', 'inferrix.controller-name', '20%',
        controller => escapeCell(controller.name)),
      new EntityTableColumn<ControllerInfo>('location', 'inferrix.location', '15%',
        controller => escapeCell(controller.location), () => ({}), false),
      new EntityTableColumn<ControllerInfo>('ip', 'inferrix.address', '15%',
        controller => escapeCell(controller.ip), () => ({}), false),
      new EntityTableColumn<ControllerInfo>('model', 'inferrix.model', '15%',
        controller => escapeCell(controller.model), () => ({}), false),
      new EntityTableColumn<ControllerInfo>('fw', 'inferrix.firmware', '10%',
        controller => escapeCell(controller.fw), () => ({}), false),
      new EntityTableColumn<ControllerInfo>('icc', 'inferrix.config-version', '80px',
        controller => escapeCell(controller.icc), () => ({}), false),
      new EntityTableColumn<ControllerInfo>('active', 'device.state', '80px',
        controller => this.controllerState(controller), controller => this.controllerStateStyle(controller), false)
    );
  }

  /**
   * A customer user has no access to the tenant-wide device listing, so the filter carries their
   * customer id and ThingsBoard's own query object picks the right URL.
   */
  resolve(): Observable<EntityTableConfig<ControllerInfo>> {
    return this.store.pipe(select(selectAuthUser), take(1)).pipe(
      tap(authUser => {
        const filter: DeviceInfoFilter = {type: INFERRIX_CONTROLLER_PROFILE};
        if (authUser.authority === Authority.CUSTOMER_USER) {
          filter.customerId = new CustomerId(authUser.customerId);
        }
        const isTenantAdmin = authUser.authority === Authority.TENANT_ADMIN;
        this.config.componentsData = {readonly: !isTenantAdmin};
        this.config.addEnabled = isTenantAdmin;
        this.config.entitiesDeleteEnabled = isTenantAdmin;
        this.config.deleteEnabled = () => isTenantAdmin;
        this.config.detailsReadonly = () => !isTenantAdmin;
        this.config.entitiesFetchFunction = pageLink =>
          this.deviceService.getDeviceInfosByQuery(new DeviceInfoQuery(pageLink, filter))
            .pipe(mergeMap(page => this.withIdentity(page)));
      }),
      map(() => this.config)
    );
  }

  /**
   * 'delete' is handled by the table itself; only the jump from the details drawer to the full
   * page needs an owner, and it is the same relative navigation the device table does.
   */
  private onControllerAction(action: EntityAction<ControllerInfo>): boolean {
    if (action.action === 'open') {
      action.event?.stopPropagation();
      this.router.navigateByUrl(
        this.router.createUrlTree([action.entity.id.id], {relativeTo: this.config.getActivatedRoute()}));
      return true;
    }
    return false;
  }

  /** The same pill the device table uses, so a controller reads the way every other device does. */
  private controllerState(controller: ControllerInfo): string {
    const translateKey = controller.active ? 'device.active' : 'device.inactive';
    const backgroundColor = controller.active ? 'rgba(25, 128, 56, 0.08)' : 'rgba(209, 39, 48, 0.08)';
    return `<div class="status" style="border-radius: 16px; height: 32px;
                line-height: 32px; padding: 0 12px; width: fit-content; background-color: ${backgroundColor}">
                ${this.translate.instant(translateKey)}
            </div>`;
  }

  private controllerStateStyle(controller: ControllerInfo): object {
    return {fontSize: '14px', color: controller.active ? '#198038' : '#d12730'};
  }

  /**
   * Adds each controller's self-reported identity to the page.
   *
   * ponytail: one attributes call per row. The page size bounds it, so this is a handful of small
   * parallel GETs; if a tenant ever runs hundreds of controllers per page, replace the whole thing
   * with one entityDataQuery selecting the attribute keys alongside the entity fields.
   */
  private withIdentity(page: PageData<DeviceInfo>): Observable<PageData<ControllerInfo>> {
    if (!page.data.length) {
      return of(page as PageData<ControllerInfo>);
    }
    return forkJoin(page.data.map(device => this.identityOf(device))).pipe(map(data => ({...page, data})));
  }

  private identityOf(device: DeviceInfo): Observable<ControllerInfo> {
    return this.attributeService.getEntityAttributes({entityType: EntityType.DEVICE, id: device.id.id},
      null, CONTROLLER_ATTRIBUTE_KEYS, {ignoreErrors: true}).pipe(
      catchError(() => of([])),
      map(attributes => {
        const byKey = new Map<string, any>(attributes.map(attribute => [attribute.key, attribute.value] as [string, any]));
        return {
          ...device,
          lastActivityTs: byKey.get('lastActivityTime'),
          // The device asserts these over MQTT in client scope; the platform recorded its own at
          // adoption. Prefer what the device says, since DHCP moves it.
          uid: byKey.get('uid') ?? byKey.get('controllerUid'),
          ip: byKey.get('ip') ?? byKey.get('controllerIp'),
          model: byKey.get('model'),
          fw: byKey.get('fw'),
          icc: byKey.get('icc'),
          mac: byKey.get('mac'),
          location: byKey.get('location'),
          deploymentName: byKey.get('name')
        } as ControllerInfo;
      }));
  }

  private loadController(deviceId: string): Observable<ControllerInfo> {
    return this.deviceService.getDeviceInfo(deviceId).pipe(mergeMap(device => this.identityOf(device)));
  }

  /**
   * Only the device record is saved. The identity fields are the controller's own attributes and
   * are not the platform's to write back, so they are dropped before the save rather than posted as
   * unknown properties.
   */
  private saveController(controller: ControllerInfo): Observable<ControllerInfo> {
    const {lastActivityTs, uid, ip, model, fw, icc, mac, location, deploymentName, ...device} = controller;
    return this.deviceService.saveDevice(device).pipe(mergeMap(saved => this.loadController(saved.id.id)));
  }

  /**
   * Adoption returns the plain device it created; the table only needs its id to reload the page,
   * and the row it then fetches carries the identity attributes.
   */
  private openAdoptDialog(): Observable<ControllerInfo> {
    return this.dialog.open<AdoptControllerDialogComponent, any, Device>(
      AdoptControllerDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog']
      }).afterClosed().pipe(map(device => device as ControllerInfo));
  }

}
