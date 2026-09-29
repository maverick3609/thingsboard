// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input,
  OnInit } from '@angular/core';
import { NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { FormSelectItem } from '@shared/models/dynamic-form.models';
import { GatewayFormComponent } from './gateway-form.component';

/**
 * An internal monitoring point's form: the generic one, plus the list of monitors it can read.
 *
 * `GATEWAY_FORM_LAYOUTS['INTERNAL.PL']` carries everything declarative. What is left is the one
 * thing a constant cannot be: the monitors a particular gateway registers. There are 98 on a plain
 * build and more with every module, and until stack 5.1.3 there was no way to offer them --
 * `GET /v2/stack-monitor` published each monitor's translated name and its current value and **not
 * its id** (D83), so the field had to be a text box an operator typed
 * `com.inferrix.stack.dao.DataPointDao.COUNT` into from memory, with a wrong id saving 201 and then
 * reading nothing for ever. Both halves are closed: the id is on the wire, and a wrong one is a 422.
 *
 * Unlike {@link MetaPointFormComponent}, the list is not keyed into an array's rows -- `monitorId` is
 * a property of the locator itself, so the key is its plain id.
 */
@Component({
  selector: 'tb-internal-point-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    // Answers the dialog's view query as well as its own type, so `save` can find every form it
    // rendered and refuse to submit an invalid one. A subclass is a different directive: without
    // this the query matching {@link GatewayFormComponent} would not see it.
    {provide: GatewayFormComponent, useExisting: forwardRef(() => InternalPointFormComponent)},
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => InternalPointFormComponent),
      multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => InternalPointFormComponent), multi: true}
  ],
  standalone: false
})
export class InternalPointFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it the field stays the text box it was. */
  @Input() deviceId: string;

  private monitors: {[id: string]: FormSelectItem[]} = Object.create(null);

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
    this.gatewayService.getMonitorValues(this.deviceId, {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: values => {
          // Sorted by the name shown rather than by the id, which is what an operator is reading.
          // An id with no name is offered under the id itself: a monitor the gateway registered
          // without a translation is still a monitor a point can read.
          const items = (values ?? [])
            .filter(monitor => !!monitor.id)
            .map(monitor => ({value: monitor.id, label: monitor.name || monitor.id}))
            .sort((left, right) => left.label.localeCompare(right.label));
          if (!items.length) {
            return;
          }
          this.monitors = {monitorId: items};
          this.refresh();
        },
        // A gateway that cannot be reached leaves the field as free text, which is what it was
        // before this component existed -- and the layout's hint says so. Failing to offer a
        // convenience is not worth an error dialog over a form the operator is in the middle of.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return this.monitors;
  }
}
