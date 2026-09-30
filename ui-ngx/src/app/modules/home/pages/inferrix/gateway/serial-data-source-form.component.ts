// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input, OnInit } from '@angular/core';
import { NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { FormSelectItem } from '@shared/models/dynamic-form.models';
import { GatewayFormComponent } from './gateway-form.component';

/**
 * A serial data source's form, which is the generic one plus the host's serial ports.
 *
 * `commPortId` is the device path the gateway opens — `/dev/ttyUSB0`, `/dev/cu.usbserial-A1`. It is
 * declared a bare string and **nothing validates it**: a wrong name saves cleanly, the source comes
 * up, and it simply never reads. That makes it the worst kind of free-text field, because the
 * failure looks like a wiring fault rather than a typo. The names are not stable either — a USB
 * adapter re-enumerates to a different `ttyUSB` number when the ports are replugged — so an
 * operator cannot reliably carry one over from another install.
 *
 * Kept separate from the layout's own `options` because the list is the gateway's answer, not a
 * constant: a layout may only narrow a field to values written down in this repository, and these
 * are read from the device at the moment the form opens.
 */
@Component({
  selector: 'tb-serial-data-source-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    // Answers the dialog's view query as well as its own type, so `save` can find every form it
    // rendered and refuse to submit an invalid one. A subclass is a different directive: without
    // this the query matching {@link GatewayFormComponent} would not see it.
    {provide: GatewayFormComponent, useExisting: forwardRef(() => SerialDataSourceFormComponent)},
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SerialDataSourceFormComponent),
      multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => SerialDataSourceFormComponent),
      multi: true}
  ],
  standalone: false
})
export class SerialDataSourceFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it the field stays the text box it was. */
  @Input() deviceId: string;

  private ports: {[id: string]: FormSelectItem[]} = Object.create(null);

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
    this.gatewayService.getSerialPorts(this.deviceId, {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ports => {
          // The path is both the value and the label: there is nothing else to show, and inventing
          // a friendlier name would hide which device the gateway will actually open.
          const items = (ports ?? [])
            .filter(port => !!port)
            .map(port => ({value: port, label: port}));
          if (!items.length) {
            return;
          }
          this.ports = {commPortId: items};
          this.refresh();
        },
        // Left as free text, which is what it was before this component existed, and what it has to
        // be for a customer user: `/v2/utilities` is tenant-admin only in the proxy allowlist, so a
        // 403 here is the expected answer for them rather than a fault worth reporting.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return this.ports;
  }
}
