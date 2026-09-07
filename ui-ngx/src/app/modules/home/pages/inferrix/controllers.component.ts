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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { forkJoin, of, Subject } from 'rxjs';
import { catchError, map, switchMap, takeUntil } from 'rxjs/operators';
import { DeviceService } from '@core/http/device.service';
import { AttributeService } from '@core/http/attribute.service';
import { PageLink } from '@shared/models/page/page-link';
import { AttributeScope } from '@shared/models/telemetry/telemetry.models';
import { EntityType } from '@shared/models/entity-type.models';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { CONTROLLER_ATTRIBUTE_KEYS, ControllerInfo, INFERRIX_CONTROLLER_PROFILE }
  from '@shared/models/inferrix-controller.models';

/**
 * Adopted controllers, with the identity each one publishes about itself.
 *
 * "Currently reporting" is the platform's own connectivity state (`active`), not anything the
 * controller asserts: the firmware's retained MQTT presence and its offline last-will are both
 * inert against the ThingsBoard transport, which implements neither.
 */
@Component({
  selector: 'tb-inferrix-controllers',
  templateUrl: './controllers.component.html',
  styleUrls: ['./controllers.component.scss'],
  standalone: false
})
export class ControllersComponent implements OnInit, OnDestroy {

  controllers: ControllerInfo[] = [];
  loading = true;
  textSearch = '';

  readonly displayedColumns = ['active', 'name', 'location', 'ip', 'model', 'fw', 'icc', 'lastActivity'];

  private destroy$ = new Subject<void>();

  isTenantAdmin = false;

  private customerId: string;

  constructor(private deviceService: DeviceService,
              private attributeService: AttributeService,
              private router: Router,
              private store: Store<AppState>) {}

  ngOnInit(): void {
    const authUser = getCurrentAuthUser(this.store);
    this.isTenantAdmin = authUser.authority === Authority.TENANT_ADMIN;
    this.customerId = authUser.customerId;
    this.reload();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  reload(): void {
    this.loading = true;
    // ponytail: one attributes call per row. The fleet per tenant is tens of devices and the page
    // size bounds it further, so this stays a handful of small parallel GETs. If a tenant ever runs
    // hundreds of controllers, swap the whole thing for one entityDataQuery that selects the
    // attribute keys alongside the entity fields.
    // A customer user has no access to the tenant-wide listing; their devices come from their own
    // customer endpoint instead.
    const pageLink = new PageLink(100, 0, this.textSearch || null);
    const page$ = this.isTenantAdmin
      ? this.deviceService.getTenantDeviceInfos(pageLink, INFERRIX_CONTROLLER_PROFILE)
      : this.deviceService.getCustomerDeviceInfos(this.customerId, pageLink, INFERRIX_CONTROLLER_PROFILE);
    page$.pipe(
      switchMap(page => page.data.length === 0
        ? of([] as ControllerInfo[])
        : forkJoin(page.data.map(device =>
          this.attributeService.getEntityAttributes(
            {entityType: EntityType.DEVICE, id: device.id.id}, null,
            CONTROLLER_ATTRIBUTE_KEYS).pipe(
            catchError(() => of([])),
            map(attributes => this.toControllerInfo(device, attributes)))))),
      takeUntil(this.destroy$)
    ).subscribe({
      next: controllers => {
        this.controllers = controllers;
        this.loading = false;
      },
      error: () => {
        this.controllers = [];
        this.loading = false;
      }
    });
  }

  open(controller: ControllerInfo): void {
    this.router.navigateByUrl(`/controllers/${controller.deviceId}`);
  }

  private toControllerInfo(device: any, attributes: any[]): ControllerInfo {
    const byKey = new Map<string, any>(attributes.map(a => [a.key, a.value]));
    return {
      deviceId: device.id.id,
      name: device.name,
      label: device.label,
      active: byKey.get('active') === true || byKey.get('active') === 'true',
      lastActivityTs: byKey.get('lastActivityTime'),
      // The device asserts these over MQTT (client scope); the platform recorded controllerIp at
      // adoption. Prefer what the device says, since DHCP moves it.
      uid: byKey.get('uid') ?? byKey.get('controllerUid'),
      ip: byKey.get('ip') ?? byKey.get('controllerIp'),
      model: byKey.get('model'),
      fw: byKey.get('fw'),
      icc: byKey.get('icc'),
      mac: byKey.get('mac'),
      location: byKey.get('location'),
      deploymentName: byKey.get('name')
    };
  }
}
