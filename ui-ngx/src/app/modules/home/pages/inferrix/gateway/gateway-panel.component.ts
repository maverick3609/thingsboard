// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Directive, Input, OnChanges, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { gatewayErrorMessage } from '@shared/models/inferrix-gateway-data.models';

/**
 * Shared behaviour for the panels on a gateway's details tabs.
 *
 * `active` is ThingsBoard's own detail-tab convention, and here it also protects the device: every
 * panel is a real call across a LAN to a small edge box, through the platform's per-device
 * connection limiter, so a panel must not talk to it until its tab is actually opened. The load
 * happens once — each panel has its own refresh — and a panel serves one gateway for its whole
 * life, since the tabs rebuild it when the device changes.
 *
 * Deliberately a near-copy of {@link ControllerPanelComponent} rather than a shared base: the two
 * differ in the one method that matters, because the gateway and the controller report errors in
 * completely different shapes.
 */
@Directive()
export abstract class GatewayPanelComponent implements OnChanges, OnDestroy {

  @Input() deviceId: string;
  @Input() readonly = false;
  @Input() active = false;

  protected destroy$ = new Subject<void>();

  private loaded = false;

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

  /** The gateway's own words where it sent any; see {@link gatewayErrorMessage}. */
  protected messageOf(error: any): string {
    return gatewayErrorMessage(error);
  }
}
