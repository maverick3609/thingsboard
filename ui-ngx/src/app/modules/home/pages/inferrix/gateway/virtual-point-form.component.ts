// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input,
  OnInit } from '@angular/core';
import { NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { FormSelectItem } from '@shared/models/dynamic-form.models';
import { GatewayFormComponent } from './gateway-form.component';

/** How many attraction candidates to offer. A gateway with more has a naming problem, not a UI one. */
const MAX_ATTRACTION_POINTS = 200;

/**
 * A virtual point's form, which is the generic one plus a list it has to ask the gateway for.
 *
 * The first per-type component, and the shape the rest follow: extend
 * {@link GatewayFormComponent}, reuse its template, and add only the behaviour a layout descriptor
 * cannot express. `GATEWAY_FORM_LAYOUTS['VIRTUAL.PL']` still carries everything that *is*
 * declarative -- the change-type gating, the conditional fields, the hidden ones -- and nothing
 * here duplicates it.
 *
 * What it adds is one field. `attractionPointXid` names the point an attractor drifts towards, and
 * the choices are every numeric point on the gateway: a lookup, not a constant, so it cannot be a
 * layout key. Until now it was a text box an operator had to paste an xid into.
 */
@Component({
  selector: 'tb-virtual-point-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => VirtualPointFormComponent),
      multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => VirtualPointFormComponent), multi: true}
  ],
  standalone: false
})
export class VirtualPointFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it the field stays the text box it was. */
  @Input() deviceId: string;

  private attractionPoints: {[id: string]: FormSelectItem[]} = Object.create(null);

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
    // Filtered by the gateway, not here: `filterField`/`filterValue` become an RQL term the
    // platform writes (`InferrixGatewayController.rql`), so this is 27 rows on the bench gateway
    // rather than every point on it. A raw query string would be refused -- the gateway parses one
    // as RQL on every verb, which is why the proxy never forwards one.
    this.gatewayService.getDataPoints(this.deviceId,
      {pageSize: MAX_ATTRACTION_POINTS, page: 0, sortProperty: 'name', sortOrder: 'ASC',
        filterField: 'dataType', filterValue: 'NUMERIC'}, {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: page => {
          const items = (page?.items ?? [])
            .filter(point => !!point.xid)
            .map(point => ({value: point.xid, label: point.name || point.xid}));
          if (!items.length) {
            return;
          }
          this.attractionPoints = {attractionPointXid: items};
          this.refresh();
        },
        // A gateway that cannot be reached leaves the field as free text, which is what it was
        // before this component existed. Failing to offer a convenience is not worth an error
        // dialog over a form the operator is in the middle of.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return this.attractionPoints;
  }
}
