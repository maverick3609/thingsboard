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
 * How many points to offer as script context. A gateway with more has a naming problem, not a UI
 * one -- the same ceiling `VIRTUAL.PL` uses for the same reason.
 */
const MAX_CONTEXT_POINTS = 200;

/**
 * A meta point's form: the generic one, plus the list of points its script can read.
 *
 * `GATEWAY_FORM_LAYOUTS['META.PL']` carries everything declarative -- the script's textarea, the
 * cron gate, the hidden fields, the defaults. What is left is the one thing a constant cannot be.
 * Each `context` entry names another point by xid, and until now that was a text box an operator had
 * to paste a generated `DP_…` into while reading it off another page.
 *
 * Unlike `VIRTUAL.PL.attractionPointXid`, the field is not a property of the locator: it sits inside
 * the `context` array, whose rows `tb-dynamic-form` draws. So the list is keyed `context.xid` and
 * lands on the array's own item properties -- see {@link GatewayFormComponent.applyNestedOptions}.
 *
 * Every data type, not just the numeric ones: a script reads a binary point's value as usefully as a
 * numeric one's, which is the difference from a virtual point's attraction target.
 */
@Component({
  selector: 'tb-meta-point-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    // Answers the dialog's view query as well as its own type, so `save` can find every form it
    // rendered and refuse to submit an invalid one. A subclass is a different directive: without
    // this the query matching {@link GatewayFormComponent} would not see it.
    {provide: GatewayFormComponent, useExisting: forwardRef(() => MetaPointFormComponent)},
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => MetaPointFormComponent), multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => MetaPointFormComponent), multi: true}
  ],
  standalone: false
})
export class MetaPointFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it the field stays the text box it was. */
  @Input() deviceId: string;

  private contextPoints: {[id: string]: FormSelectItem[]} = Object.create(null);

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
    this.gatewayService.getDataPoints(this.deviceId,
      {pageSize: MAX_CONTEXT_POINTS, page: 0, sortProperty: 'name', sortOrder: 'ASC'},
      {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: page => {
          const items = (page?.items ?? [])
            .filter(point => !!point.xid)
            .map(point => ({value: point.xid, label: point.name || point.xid}));
          if (!items.length) {
            return;
          }
          this.contextPoints = {'context.xid': items};
          this.refresh();
        },
        // A gateway that cannot be reached leaves the field as free text, which is what it was
        // before this component existed. Failing to offer a convenience is not worth an error
        // dialog over a form the operator is in the middle of.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return this.contextPoints;
  }
}
