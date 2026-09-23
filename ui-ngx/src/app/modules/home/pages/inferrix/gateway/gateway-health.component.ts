// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayReachability, gatewayReachabilityLabel,
  gatewayReachabilityTone, GatewayReachabilityTone } from '@shared/models/inferrix-gateway.models';

/**
 * Whether the platform can reach this gateway, and if not, why.
 *
 * The reason is the whole point. A gateway lives on a LAN or a VPN, so "unreachable" is the answer
 * an operator expects and the one that teaches them nothing — while a rejected token, an
 * under-privileged one, a changed certificate and a gateway that was never finished being adopted
 * are four different problems in four different places, none of them the network.
 *
 * Loads only when its tab is open. The probe is a real call to the device through the platform's
 * per-device connection limiter, so a panel that probed on construction would spend one on every
 * details drawer an operator happens to open.
 */
@Component({
  selector: 'tb-gateway-health',
  templateUrl: './gateway-health.component.html',
  styleUrls: ['./gateway-health.component.scss'],
  standalone: false
})
export class GatewayHealthComponent implements OnChanges {

  @Input() active: boolean;
  @Input() deviceId: string;

  readonly labelOf = gatewayReachabilityLabel;

  reachability: GatewayReachability;
  loading = false;

  /** The gateway the panel currently describes, so a re-activation does not re-probe. */
  private probedDeviceId: string;

  constructor(private gatewayService: InferrixGatewayService,
              private cd: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges) {
    if ((changes.active || changes.deviceId) && this.active && this.deviceId
        && this.deviceId !== this.probedDeviceId) {
      this.probe();
    }
  }

  probe() {
    if (!this.deviceId) {
      return;
    }
    this.probedDeviceId = this.deviceId;
    this.loading = true;
    // The endpoint never errors -- it turns every failure into a reason -- so there is no error
    // branch to write here. A transport failure is the one case left, and that is the browser
    // losing the platform, not the platform losing the gateway.
    this.gatewayService.getReachability(this.deviceId, {ignoreLoading: true}).subscribe({
      next: reachability => {
        this.reachability = reachability;
        this.loading = false;
        // An entity-details tab only redraws when something flips its loading flag, and
        // ignoreLoading means nothing did.
        this.cd.markForCheck();
      },
      error: () => {
        this.reachability = {
          reachable: false, reason: 'UNREACHABLE', checkedAt: Date.now(),
          message: ''
        };
        this.loading = false;
        this.cd.markForCheck();
      }
    });
  }

  get tone(): GatewayReachabilityTone {
    return this.reachability ? gatewayReachabilityTone(this.reachability.reason) : 'error';
  }
}
