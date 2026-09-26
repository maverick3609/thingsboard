// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input, OnInit } from '@angular/core';
import { NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { FormSelectItem } from '@shared/models/dynamic-form.models';
import { GatewayFormComponent } from './gateway-form.component';

/**
 * A BACnet data source's form, which is the generic one plus the gateway's local devices.
 *
 * `localDeviceConfig` names the gateway's own side of the BACnet network — which NIC it binds,
 * which UDP port it listens on, what instance number it announces. It is declared a bare string
 * and holds a local device's `id`, and `BACnetDataSourceDefinition.validate` looks that id up and
 * refuses a value that resolves to nothing. So this is not a convenience: without the list an
 * operator has to find the id by hand, and a BACnet data source cannot be saved without one.
 *
 * Used for both BACnet/IP and BACnet MS/TP — `localDeviceConfig` is declared on
 * `BACnetDataSourceVO`, which both extend — with {@link transport} deciding which local devices the
 * list offers.
 */
@Component({
  selector: 'tb-bacnet-data-source-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => BacnetDataSourceFormComponent),
      multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => BacnetDataSourceFormComponent),
      multi: true}
  ],
  standalone: false
})
export class BacnetDataSourceFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it the field stays the text box it was. */
  @Input() deviceId: string;

  /**
   * Which transport this data source speaks, so the picker offers only local devices that match.
   *
   * Not cosmetic. Nothing on the gateway checks that a source's transport agrees with the local
   * device it names — `BACnetDataSourceDefinition.validate` only checks that the id resolves, and
   * `LocalDeviceFactory` builds whatever the config says. So an MS/TP source naming an IP local
   * device is accepted and then quietly speaks BACnet/IP, on a bus that is not there.
   */
  @Input() transport: 'IP' | 'MSTP';

  private localDevices: {[id: string]: FormSelectItem[]} = Object.create(null);

  constructor(fb: UntypedFormBuilder,
              destroyRef: DestroyRef,
              cd: ChangeDetectorRef,
              private gatewayService: InferrixGatewayService) {
    super(fb, destroyRef, cd);
  }

  ngOnInit(): void {
    if (!this.deviceId) {
      return;
    }
    this.gatewayService.getBacnetLocalDevices(this.deviceId, {ignoreLoading: true,
      ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: devices => {
          const items = (devices ?? [])
            .filter(device => !!device.id)
            // A device with no type is kept: the gateway has always sent one, and dropping a row
            // over a field this form does not otherwise read would hide a usable local device.
            .filter(device => !this.transport || !device.type || device.type === this.transport)
            .map(device => ({
              value: device.id,
              // Both halves, because neither is enough on its own: two local devices on different
              // networks may announce the same instance number, and `deviceName` is free text the
              // operator typed on the gateway and may have left at its default.
              label: device.deviceName
                ? `${device.deviceName} (${device.deviceId})`
                : String(device.deviceId ?? device.id)
            }));
          if (!items.length) {
            return;
          }
          this.localDevices = {localDeviceConfig: items};
          this.refresh();
        },
        // Left as free text, which is what it was before this component existed. A gateway that
        // cannot be reached is already reported by the panel that opened this dialog.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return this.localDevices;
  }
}
