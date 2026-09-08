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

import { Component } from '@angular/core';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityTabsComponent } from '@home/components/entity/entity-tabs.component';
import { ControllerInfo } from '@shared/models/inferrix-controller.models';

/**
 * Everything about a controller that is not the device record itself.
 *
 * Every panel takes `active` and only talks to the device once its tab is opened. That is not a
 * nicety: the firmware serves two clients at a time and the platform blocks up to 30 s on a
 * per-device semaphore, so loading all five at once would queue eleven calls and hold a request
 * thread each while they wait.
 */
@Component({
  selector: 'tb-inferrix-controller-tabs',
  templateUrl: './controller-tabs.component.html',
  styleUrls: [],
  standalone: false
})
export class ControllerTabsComponent extends EntityTabsComponent<ControllerInfo> {

  constructor(protected store: Store<AppState>) {
    super(store);
  }

  /** Writes go through the platform proxy as the tenant admin; a customer user may only read. */
  get readonly(): boolean {
    return !!this.entitiesTableConfig?.componentsData?.readonly;
  }

}
