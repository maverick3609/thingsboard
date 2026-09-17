// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Directive, Input, OnChanges, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { controllerRecordError } from '@shared/models/inferrix-controller.models';

/**
 * Shared behaviour for the panels on a controller's details tabs.
 *
 * The `active` input is ThingsBoard's own detail-tab convention (`tb-attribute-table`,
 * `tb-relation-table` and the rest all take it), and here it also protects the device: the firmware
 * serves two clients at a time, so a panel must not talk to it until its tab is actually opened.
 * The load happens once — everything on these panels has its own refresh control — and a panel serves
 * one device for its whole life: the tabs rebuild it when the device changes.
 */
@Directive()
export abstract class ControllerPanelComponent implements OnChanges, OnDestroy {

  @Input() deviceId: string;
  @Input() readonly = false;
  @Input() active = false;

  protected destroy$ = new Subject<void>();

  private loaded = false;

  /**
   * Loads here rather than in an `active` setter, because inputs are set in template order and the
   * templates bind `active` first. A panel created on a tab that is already open (the tune panel
   * inside Logic, or any panel rebuilt for a new device) used to load before it knew the device, and
   * before `readonly`. By the time this hook runs, every input is set.
   */
  ngOnChanges(): void {
    if (this.active && this.deviceId && !this.loaded) {
      this.loaded = true;
      this.load();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Called once, the first time this panel's tab is opened. */
  protected abstract load(): void;

  /**
   * The device's own error text where it sent one, so a 400 says which field it rejected.
   *
   * Two different speakers answer these calls. The platform refuses in ThingsBoard's shape, with a
   * `message`; the controller refuses in its own, a bare `{"error":"name"}` with no message at all,
   * which used to come out here as "Request failed" and threw away the only useful word in it.
   */
  protected messageOf(error: any): string {
    return error?.error?.message
      || controllerRecordError(error, null)
      || error?.message
      || 'Request failed';
  }
}
