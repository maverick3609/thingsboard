// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormPropertyType } from '@shared/models/dynamic-form.models';
import { GATEWAY_FORM_LAYOUTS,
  gatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';

/**
 * The layouts are data, and what makes them right is agreement with the gateway's own Java rather
 * than anything this repository can compile. These lock in the facts that were read out of it, so
 * that a later edit to the table has to be a deliberate one.
 */
describe('gateway form layouts', () => {

  const virtual = GATEWAY_FORM_LAYOUTS['VIRTUAL.PL'];

  it('offers exactly the change types ChangeTypeVO.getChangeTypes admits per data type', () => {
    const table = virtual.gatedOptions.changeType.table;
    const values = (dataType: string) => table[dataType].map(item => item.value);
    expect(values('BINARY'))
      .toEqual(['ALTERNATE_BOOLEAN', 'NO_CHANGE', 'RANDOM_BOOLEAN']);
    expect(values('MULTISTATE'))
      .toEqual(['INCREMENT_MULTISTATE', 'NO_CHANGE', 'RANDOM_MULTISTATE']);
    expect(values('NUMERIC'))
      .toEqual(['BROWNIAN', 'INCREMENT_ANALOG', 'NO_CHANGE', 'RANDOM_ANALOG', 'ANALOG_ATTRACTOR',
        'DECREMENT_ANALOG']);
    expect(values('ALPHANUMERIC')).toEqual(['NO_CHANGE']);
  });

  it('uses INCREMENT_MULTISTATE, not the dead MULTISTATE case the gateway webapp switches on', () => {
    const multistate = virtual.gatedOptions.changeType.table.MULTISTATE.map(item => item.value);
    expect(multistate).toContain('INCREMENT_MULTISTATE');
    expect(multistate).not.toContain('MULTISTATE');
  });

  it('shows each change-specific field for exactly the change types whose VO declares it', () => {
    const shownFor = (id: string) => virtual.visibleWhen[id].values;
    expect(shownFor('values'))
      .toEqual(['INCREMENT_MULTISTATE', 'RANDOM_MULTISTATE']);
    expect(shownFor('roll'))
      .toEqual(['INCREMENT_MULTISTATE', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG']);
    expect(shownFor('min'))
      .toEqual(['BROWNIAN', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG', 'RANDOM_ANALOG']);
    expect(shownFor('max')).toEqual(shownFor('min'));
    expect(shownFor('change')).toEqual(['INCREMENT_ANALOG', 'DECREMENT_ANALOG']);
    expect(shownFor('maxChange')).toEqual(['BROWNIAN', 'ANALOG_ATTRACTOR']);
    expect(shownFor('volatility')).toEqual(['ANALOG_ATTRACTOR']);
    expect(shownFor('attractionPointXid')).toEqual(['ANALOG_ATTRACTOR']);
  });

  it('leaves startValue unconditional, since every change type carries one', () => {
    expect(virtual.visibleWhen.startValue).toBeUndefined();
  });

  it('makes startValue a true/false list for a binary point and free text for the rest', () => {
    const gate = virtual.gatedOptions.startValue;
    expect(gate.by).toBe('dataType');
    expect(gate.table.BINARY.map(item => item.value)).toEqual(['true', 'false']);
    expect(Object.keys(gate.table)).toEqual(['BINARY']);
    expect(gate.unlisted).toBe(FormPropertyType.text);
  });

  it('hides the point-level settable, which the gateway overwrites from the locator', () => {
    expect(GATEWAY_FORM_LAYOUTS.DataPointModel.hidden).toContain('settable');
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL.PL'].hidden).not.toContain('settable');
  });

  it('marks a data source type as worked through even when it needs no overrides', () => {
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL.DS']).toEqual({});
  });

  it('starts a new virtual locator on what VirtualPointLocatorVO starts on', () => {
    expect(virtual.defaults).toEqual({dataType: 'BINARY', changeType: 'ALTERNATE_BOOLEAN'});
  });

  it('defaults only to a value its own gate admits', () => {
    const gate = virtual.gatedOptions.changeType;
    const admitted = gate.table[virtual.defaults[gate.by]].map(item => item.value);
    expect(admitted).toContain(virtual.defaults.changeType);
  });

  it('answers nothing for a prototype key a gateway could name a model type', () => {
    ['constructor', 'toString', 'hasOwnProperty', 'valueOf'].forEach(key =>
      expect(gatewayFormLayout(key)).withContext(key).toBeUndefined());
    expect(gatewayFormLayout('VIRTUAL.PL')).toBe(GATEWAY_FORM_LAYOUTS['VIRTUAL.PL']);
    expect(gatewayFormLayout(undefined)).toBeUndefined();
  });

  it('narrows only fields the renderer lays out itself', () => {
    // A layout may only put an option list on a scalar. `GatewayFormComponent.build` decides which
    // properties get a control from the schema's own type, so naming an array or a nested object
    // here would ask its template for a control that was never created.
    const scalars = new Set(['dataType', 'changeType', 'startValue', 'min', 'max', 'change',
      'maxChange', 'volatility', 'attractionPointXid', 'roll', 'settable']);
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      [...Object.keys(layout.options ?? {}), ...Object.keys(layout.gatedOptions ?? {})]
        .forEach(id => expect(scalars.has(id)).withContext(`${modelType}.${id}`).toBe(true));
    });
  });

  it('names no field in both hidden and advanced', () => {
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      (layout.advanced ?? []).forEach(id =>
        expect(hidden.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('never names the same field in two explicit rows', () => {
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const ids = (layout.rows ?? []).flat();
      expect(new Set(ids).size).withContext(modelType).toBe(ids.length);
    });
  });
});
