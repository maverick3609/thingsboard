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
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { forkJoin, of, Subject } from 'rxjs';
import { catchError, map, switchMap, takeUntil } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { DeviceService } from '@core/http/device.service';
import { AttributeService } from '@core/http/attribute.service';
import { EntityType } from '@shared/models/entity-type.models';
import { Authority } from '@shared/models/authority.enum';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { CONTROLLER_ATTRIBUTE_KEYS, ControllerHealth, ControllerInfo }
  from '@shared/models/inferrix-controller.models';

/**
 * One adopted controller: what it says it is, how it is doing, and everything that can be changed
 * on it.
 *
 * The identity block comes from the device's own attributes, which arrive over MQTT and are
 * therefore available whether or not the controller is reachable over REST. Everything else is a
 * live call through the platform's proxy and needs the device on the network right now.
 */
@Component({
  selector: 'tb-inferrix-controller',
  templateUrl: './controller.component.html',
  styleUrls: ['./controller.component.scss'],
  standalone: false
})
export class ControllerComponent extends PageComponent implements OnInit, OnDestroy {

  deviceId: string;
  controller: ControllerInfo;
  health: ControllerHealth;
  info: {[key: string]: any};

  loading = true;
  healthLoading = false;
  healthError: string;
  readonly = true;

  private destroy$ = new Subject<void>();

  constructor(protected store: Store<AppState>,
              private route: ActivatedRoute,
              private router: Router,
              private deviceService: DeviceService,
              private attributeService: AttributeService,
              private controllerService: InferrixControllerService) {
    super();
  }

  ngOnInit(): void {
    this.readonly = getCurrentAuthUser(this.store).authority !== Authority.TENANT_ADMIN;
    this.route.params.pipe(takeUntil(this.destroy$)).subscribe(params => {
      this.deviceId = params.entityId;
      this.reload();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    super.ngOnDestroy();
  }

  reload(): void {
    this.loading = true;
    this.deviceService.getDevice(this.deviceId).pipe(
      switchMap(device => forkJoin([
        of(device),
        this.attributeService.getEntityAttributes({entityType: EntityType.DEVICE, id: this.deviceId}, null,
          CONTROLLER_ATTRIBUTE_KEYS).pipe(catchError(() => of([])))
      ])),
      map(([device, attributes]) => this.toControllerInfo(device, attributes)),
      takeUntil(this.destroy$)
    ).subscribe({
      next: controller => {
        this.controller = controller;
        this.loading = false;
        this.reloadLive();
      },
      error: () => this.loading = false
    });
  }

  /** Health and info are two calls the device can only answer while it is reachable. */
  reloadLive(): void {
    this.healthLoading = true;
    this.healthError = null;
    forkJoin([
      this.controllerService.proxy<ControllerHealth>(this.deviceId, 'GET', '/api/v1/health',
        null, {ignoreErrors: true}).pipe(catchError(() => of(null))),
      this.controllerService.proxy<any>(this.deviceId, 'GET', '/api/v1/info',
        null, {ignoreErrors: true}).pipe(catchError(() => of(null)))
    ]).pipe(takeUntil(this.destroy$)).subscribe(([health, info]) => {
      this.health = health;
      this.info = info;
      this.healthLoading = false;
      if (!health && !info) {
        this.healthError = 'inferrix.controller-unreachable';
      }
    });
  }

  back(): void {
    this.router.navigateByUrl('/controllers');
  }

  mqttStateLabel(): string {
    return label(this.health?.mqtt?.state, MQTT_STATES);
  }

  timeSyncLabel(): string {
    return label(this.health?.time_sync?.state, TIME_SYNC_STATES);
  }

  uptimeLabel(): string {
    const ms = this.health?.uptime_ms;
    if (!ms) {
      return '—';
    }
    const seconds = Math.floor(ms / 1000);
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return days > 0 ? `${days}d ${hours}h` : hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
  }

  private toControllerInfo(device: any, attributes: any[]): ControllerInfo {
    const byKey = new Map<string, any>(attributes.map(a => [a.key, a.value]));
    return {
      deviceId: device.id.id,
      name: device.name,
      label: device.label,
      active: byKey.get('active') === true || byKey.get('active') === 'true',
      lastActivityTs: byKey.get('lastActivityTime'),
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

/**
 * The firmware's `mqtt.state` and `time_sync.state`, per INTEGRATION-API.md. Backoff is not
 * distinguished from connecting on the wire, so it cannot be distinguished here; anything outside
 * the documented range is shown as the raw number rather than guessed at.
 */
const MQTT_STATES: {[state: number]: string} = {
  0: 'disabled',
  1: 'connecting',
  2: 'connected'
};

const TIME_SYNC_STATES: {[state: number]: string} = {
  0: 'unset',
  1: 'rtc',
  2: 'synced'
};

const label = (state: number | string, states: {[state: number]: string}): string => {
  if (state === undefined || state === null) {
    return '—';
  }
  return typeof state === 'number' ? states[state] ?? String(state) : String(state);
};
