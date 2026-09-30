// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { UntypedFormBuilder } from '@angular/forms';
import { MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { EMPTY } from 'rxjs';
import { GATEWAY_FORM_LAYOUTS } from '@shared/models/inferrix-gateway-layout.models';
import { GatewayModelDialogComponent,
  GatewayModelDialogData } from './gateway-model-dialog.component';

/**
 * The one decision this dialog makes before anything is rendered: whether the model type it was
 * handed can be saved at all.
 *
 * It used to be made by the caller, and there are six callers — so a type marked `unsavable` opened
 * behind a working Save button from five of them. The check lives here now, and this is what says so.
 *
 * Built through `TestBed.runInInjectionContext` rather than by hand: `PageComponent` reaches for the
 * store with `inject`, so the constructor needs a context even though nothing here is rendered.
 */
describe('gateway model dialog read-only gate', () => {

  beforeEach(() => TestBed.configureTestingModule({
    providers: [
      {provide: Store, useValue: {pipe: () => EMPTY}},
      {provide: Router, useValue: {events: EMPTY}}
    ]
  }));

  const build = (data: Partial<GatewayModelDialogData>): GatewayModelDialogData => {
    const full = {title: 'x', model: {}, properties: [], readonly: false, ...data};
    TestBed.runInInjectionContext(() => new GatewayModelDialogComponent(
      TestBed.inject(Store), TestBed.inject(Router), full,
      {close: () => {}} as MatDialogRef<GatewayModelDialogComponent, any>,
      new UntypedFormBuilder(),
      {instant: (key: string) => `translated:${key}`} as TranslateService));
    return full;
  };

  afterEach(() => TestBed.resetTestingModule());

  it('locks a model type the gateway destroys on write, and says why', () => {
    // `MODBUS_SLAVE_DEVICE.DS` is the live case: `toVO` drops the controller id, so there is no body
    // Cortex could send that would be safe -- including one that changes only the name (D131).
    const layout = GATEWAY_FORM_LAYOUTS['MODBUS_SLAVE_DEVICE.DS'];
    expect(layout.unsavable).toBeTruthy();
    const data = build({layout});
    expect(data.readonly).toBe(true);
    expect(data.readonlyNote).toBe(`translated:${layout.unsavable}`);
  });

  it('locks it from a point dialog too, where the flag is on the locator', () => {
    // A point dialog passes the locator's layout separately, so reading `layout` alone would miss a
    // locator marked unsavable. No locator is marked today; the branch is what stops that being a
    // silent gap the next time one is.
    const data = build({layout: undefined, locatorLayout: {unsavable: 'a.key'}});
    expect(data.readonly).toBe(true);
    expect(data.readonlyNote).toBe('translated:a.key');
  });

  it('leaves a savable type alone, and keeps a caller\'s own reason', () => {
    expect(build({layout: GATEWAY_FORM_LAYOUTS['MODBUS.DS']}).readonly).toBe(false);
    // An event handler that runs commands is read-only for a reason of the caller's, not the type's.
    const own = build({readonly: true, readonlyNote: 'handler.runs.commands'});
    expect(own.readonlyNote).toBe('handler.runs.commands');
    // And a caller's reason is not overwritten by the type's.
    const both = build({readonly: true, readonlyNote: 'caller.reason',
      layout: GATEWAY_FORM_LAYOUTS['MODBUS_SLAVE_DEVICE.DS']});
    expect(both.readonlyNote).toBe('caller.reason');
  });
});
