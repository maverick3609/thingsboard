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

import { Directive, Input, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';

/**
 * Shared behaviour for the panels on a controller's details tabs.
 *
 * The `active` input is ThingsBoard's own detail-tab convention (`tb-attribute-table`,
 * `tb-relation-table` and the rest all take it), and here it also protects the device: the firmware
 * serves two clients at a time, so a panel must not talk to it until its tab is actually opened.
 * The load happens once — everything on these panels has its own refresh control.
 */
@Directive()
export abstract class ControllerPanelComponent implements OnDestroy {

  @Input() deviceId: string;
  @Input() readonly = false;

  @Input()
  set active(active: boolean) {
    this.activeValue = active;
    if (active && !this.loaded) {
      this.loaded = true;
      this.load();
    }
  }

  get active(): boolean {
    return this.activeValue;
  }

  protected destroy$ = new Subject<void>();

  private activeValue = false;
  private loaded = false;

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Called once, the first time this panel's tab is opened. */
  protected abstract load(): void;

  /** The device's own error text where it sent one, so a 400 says which field it rejected. */
  protected messageOf(error: any): string {
    return error?.error?.message || error?.message || 'Request failed';
  }
}
