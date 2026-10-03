// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { GATEWAY_FORM_LAYOUTS, GatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';
import modelIds from './inferrix-gateway-schema.model-ids.json';

/**
 * Every field a layout names, against the fields a real gateway publishes.
 *
 * The other layout specs assert what a layout *says*. This one asserts that what it says lands on
 * something: a layout that hides, locks, hints at, narrows or rows up a field the gateway does not
 * publish is inert, and inert in silence — `pack` filters a row's ids against the properties it was
 * handed, and a hint for an absent id is never rendered. A typo and a field a stack release removed
 * look identical, and neither fails any other spec in this file's neighbourhood.
 *
 * **The fixture is measured, not written.** `inferrix-gateway-schema.model-ids.json` holds the
 * top-level `FormProperty` ids the mapper produces for each model type, taken from the schema
 * document of the gateway Cortex is adopted to (`Inferrix Gateway 155`, stack **5.1.1**) read
 * through Cortex's own proxy on 2026-10-04. Ids rather than the whole document on purpose: the
 * document is 650 KB and what this spec needs from it is 22 KB.
 *
 * Two facts worth keeping from that read, because they are the strongest statement available about
 * this feature's coverage:
 *
 * - the gateway publishes **63** data source types and **61** point locators, and the layout table
 *   covers 62 and 60 of them. The only two it does not are `OPC.DS` and `OPC.PL`, deferred by the
 *   user.
 * - no layout key names a model type the gateway does not publish, and no model type has a
 *   duplicate top-level id.
 *
 * **What this fixture does not retire** is the risk it was first written up as retiring. The
 * gateway Cortex is adopted to answers on `192.168.221.7:8443`, and that address over the VPN is
 * this development machine: `/rest/v2/about` on it reports `hostName: Deeps-MacBook-Pro.local` and
 * the same twelve data sources. So the read confirmed the mapper against the stack the rules were
 * read from, not against a second one. It also found the two apart in a way worth recording: that
 * stack reports `stackVersion 5.1.1` while the source tree beside it is `<stack.version>5.1.3`, so
 * every rule here was *read* from 5.1.3 sources and *measured* against a 5.1.1 runtime. A real edge
 * server on a different release is still unverified.
 *
 * To refresh the fixture after a stack upgrade: read `GET /api/inferrix/gateways/{deviceId}/schemas`
 * through Cortex, and for each key of {@link GATEWAY_FORM_LAYOUTS} map the schema with
 * `schemaToFormProperties` (or `componentToFormProperties` for `DataPointModel`) and keep
 * `props.map(p => p.id)`.
 */
describe('gateway form layouts against a real gateway\'s schema', () => {

  const published: {[modelType: string]: string[]} = modelIds.topLevelIds;

  /** Every id a layout mentions, whatever key it mentions it under. */
  const named = (layout: GatewayFormLayout): string[] => {
    const l = layout as any;
    return [
      ...(l.hidden ?? []), ...(l.readonly ?? []), ...(l.advanced ?? []),
      ...(l.required ?? []), ...(l.sendEmpty ?? []), ...(l.rows ?? []).flat(),
      ...Object.keys(l.hints ?? {}), ...Object.keys(l.options ?? {}),
      ...Object.keys(l.gatedOptions ?? {}), ...Object.keys(l.visibleWhen ?? {}),
      ...Object.keys(l.defaults ?? {}), ...Object.keys(l.types ?? {}),
      ...Object.keys(l.min ?? {})
    ];
  };

  it('was measured against the gateway Cortex actually talks to', () => {
    expect(modelIds.measuredAgainst.stackVersion).toBe('5.1.1');
    expect(modelIds.measuredAgainst.dataSourceTypes).toBe(63);
    expect(modelIds.measuredAgainst.pointLocatorTypes).toBe(61);
  });

  /**
   * The one inert reference in the table, and why it is allowed to stay.
   *
   * `MODBUS_SLAVE_DEVICE.DS` and `MODBUS_SLAVE_DEVICE_POLLING.DS` share one layout object, because
   * they differ by `timePeriod` and `quantize` and nothing else. `timePeriod` is in that layout's
   * rows and hints for the polling variant's sake, and on the non-polling one it lands on nothing.
   * Splitting the layout in two to avoid it would be two near-identical objects to keep in step.
   */
  const INERT = ['MODBUS_SLAVE_DEVICE.DS.timePeriod'];

  it('has a published field for every field every layout names', () => {
    const unknown: string[] = [];
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const ids = published[modelType];
      // A model type with no entry is caught by the next spec; skipping it here keeps this one's
      // failure message about the thing it is named for.
      if (!ids) {
        return;
      }
      named(layout).filter(id => !ids.includes(id))
        .filter(id => !INERT.includes(`${modelType}.${id}`))
        .forEach(id => unknown.push(`${modelType}.${id}`));
    });
    expect(unknown).toEqual([]);
  });

  it('allows that reference only because the twin it is shared with publishes the field', () => {
    // The exemption is worth no more than this assertion. If the polling variant ever stops
    // declaring `timePeriod`, the shared layout is naming a field neither type has.
    expect(published['MODBUS_SLAVE_DEVICE_POLLING.DS']).toContain('timePeriod');
    expect(published['MODBUS_SLAVE_DEVICE.DS']).not.toContain('timePeriod');
    expect(GATEWAY_FORM_LAYOUTS['MODBUS_SLAVE_DEVICE.DS'])
      .toBe(GATEWAY_FORM_LAYOUTS['MODBUS_SLAVE_DEVICE_POLLING.DS']);
  });

  it('names no model type the gateway does not publish', () => {
    expect(Object.keys(GATEWAY_FORM_LAYOUTS).filter(modelType => !published[modelType]))
      .toEqual([]);
  });

  it('covers every published type but the two the user deferred', () => {
    // The fixture holds only the types a layout exists for, so the gap is counted from the totals
    // the same read recorded. 63 - 62 and 61 - 60, and both missing ones are OPC.
    const sources = Object.keys(GATEWAY_FORM_LAYOUTS).filter(modelType => modelType.endsWith('.DS'));
    const locators = Object.keys(GATEWAY_FORM_LAYOUTS).filter(modelType => modelType.endsWith('.PL'));
    expect(modelIds.measuredAgainst.dataSourceTypes - sources.length).toBe(1);
    expect(modelIds.measuredAgainst.pointLocatorTypes - locators.length).toBe(1);
    expect(GATEWAY_FORM_LAYOUTS['OPC.DS']).toBeUndefined();
    expect(GATEWAY_FORM_LAYOUTS['OPC.PL']).toBeUndefined();
  });

  it('gives no model type two top-level fields with the same id', () => {
    // A second property with the same id would overwrite the first's control, and a layout naming it
    // would reach whichever the mapper emitted last. Measured as clean across all 123 entries; the
    // ids that do repeat in the document all repeat across nesting levels, where each fieldset and
    // each array row has a form of its own.
    const dup: string[] = [];
    Object.entries(published).forEach(([modelType, ids]) =>
      ids.filter((id, i) => ids.indexOf(id) !== i)
        .forEach(id => dup.push(`${modelType}.${id}`)));
    expect(dup).toEqual([]);
  });
});
