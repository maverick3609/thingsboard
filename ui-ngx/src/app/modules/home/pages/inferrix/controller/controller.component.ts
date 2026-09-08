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

import { ChangeDetectorRef, Component, Inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { EntityComponent } from '@home/components/entity/entity.component';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { EntityType } from '@shared/models/entity-type.models';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerHealth, ControllerInfo } from '@shared/models/inferrix-controller.models';

/**
 * The Details tab of an adopted controller.
 *
 * The editable part is the plain device record — name, label, description — so it is the standard
 * entity form. Below it sits what the controller says about itself: the identity block comes from
 * attributes and so is readable whether or not the device is up, while health is a live call
 * through the proxy and needs the controller on the network right now.
 */
@Component({
  selector: 'tb-inferrix-controller',
  templateUrl: './controller.component.html',
  styleUrls: [],
  standalone: false
})
export class ControllerComponent extends EntityComponent<ControllerInfo> {

  entityType = EntityType;

  health: ControllerHealth;
  info: {[key: string]: any};
  healthLoading = false;
  healthError: string;

  /** The controller the health block currently describes. */
  private liveDeviceId: string;

  constructor(protected store: Store<AppState>,
              @Inject('entity') protected entityValue: ControllerInfo,
              @Inject('entitiesTableConfig') protected entitiesTableConfigValue: EntityTableConfig<ControllerInfo>,
              public fb: UntypedFormBuilder,
              protected cd: ChangeDetectorRef,
              private controllerService: InferrixControllerService) {
    super(store, fb, entityValue, entitiesTableConfigValue, cd);
  }

  buildForm(entity: ControllerInfo): UntypedFormGroup {
    return this.fb.group({
      name: [entity ? entity.name : '', [Validators.required, Validators.maxLength(255)]],
      label: [entity ? entity.label : '', [Validators.maxLength(255)]],
      additionalInfo: this.fb.group({
        description: [entity && entity.additionalInfo ? entity.additionalInfo.description : '']
      })
    });
  }

  updateForm(entity: ControllerInfo) {
    this.entityForm.patchValue({
      name: entity.name,
      label: entity.label,
      additionalInfo: {description: entity.additionalInfo ? entity.additionalInfo.description : ''}
    });
    // The details panel re-sets the entity whenever edit mode is toggled, and the device serves two
    // clients at a time — so only a different controller is worth two live calls.
    if (entity?.id?.id !== this.liveDeviceId) {
      this.liveDeviceId = entity?.id?.id;
      this.health = null;
      this.info = null;
      this.reloadLive();
    }
  }

  hideDelete() {
    return this.entitiesTableConfig ? !this.entitiesTableConfig.deleteEnabled(this.entity) : false;
  }

  /** Health and info are the two calls the device can only answer while it is reachable. */
  reloadLive(): void {
    if (!this.entity?.id?.id) {
      return;
    }
    const deviceId = this.entity.id.id;
    this.healthLoading = true;
    this.healthError = null;
    forkJoin([
      this.controllerService.proxy<ControllerHealth>(deviceId, 'GET', '/api/v1/health',
        null, {ignoreErrors: true}).pipe(catchError(() => of(null))),
      this.controllerService.proxy<any>(deviceId, 'GET', '/api/v1/info',
        null, {ignoreErrors: true}).pipe(catchError(() => of(null)))
    ]).subscribe(([health, info]) => {
      this.health = health;
      this.info = info;
      this.healthLoading = false;
      this.healthError = (!health && !info) ? 'inferrix.controller-unreachable' : null;
      this.cd.markForCheck();
    });
  }

  mqttStateLabel(): string {
    return stateLabel(this.health?.mqtt?.state, MQTT_STATES);
  }

  timeSyncLabel(): string {
    return stateLabel(this.health?.time_sync?.state, TIME_SYNC_STATES);
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

const stateLabel = (state: number | string, states: {[state: number]: string}): string => {
  if (state === undefined || state === null) {
    return '—';
  }
  return typeof state === 'number' ? states[state] ?? String(state) : String(state);
};
