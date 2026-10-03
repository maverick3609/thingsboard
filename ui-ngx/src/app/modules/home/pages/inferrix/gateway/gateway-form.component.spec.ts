// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, DestroyRef, SimpleChanges } from '@angular/core';
import { UntypedFormBuilder } from '@angular/forms';
import { FormFieldSetProperty, FormProperty, FormPropertyType,
  FormSelectItem } from '@shared/models/dynamic-form.models';
import { GATEWAY_FORM_LAYOUTS } from '@shared/models/inferrix-gateway-layout.models';
import { GATEWAY_ADVANCED_GROUP } from '@shared/models/inferrix-gateway-schema.models';
import { GatewayFormComponent } from './gateway-form.component';

/**
 * The two things a layout can say that reach past the fields it names: a rendering type, and an
 * option list on a field inside a delegated array's rows. Both were added for `META.PL` — a script
 * body in a single-line box, and a context entry naming another point by xid.
 *
 * Built by hand rather than through `TestBed`. The component takes its `DestroyRef` and
 * `ChangeDetectorRef` as constructor arguments, so nothing here needs an injection context, and a
 * form this small does not need to be rendered to be asked what it built.
 */
describe('gateway form layout mechanics', () => {

  const property = (id: string, type: FormPropertyType, extra: any = {}): FormProperty =>
    ({id, name: id, type, default: null, ...extra} as FormProperty);

  const CONTEXT_ITEM_FIELDS = [property('xid', FormPropertyType.text),
    property('variableName', FormPropertyType.text),
    property('contextUpdate', FormPropertyType.switch)];

  const PROPERTIES = [
    property('script', FormPropertyType.text),
    property('context', FormPropertyType.array,
      {arrayItemType: FormPropertyType.fieldset, properties: CONTEXT_ITEM_FIELDS})
  ];

  const POINTS: FormSelectItem[] = [{value: 'DP_1', label: 'Outside air'},
    {value: 'DP_2', label: 'Return air'}];

  /** A form with a looked-up list, the way a per-type component supplies one. */
  class TestForm extends GatewayFormComponent {
    lists: {[id: string]: FormSelectItem[]} = Object.create(null);
    protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
      return this.lists;
    }
    /** `refresh` is what a component calls when its lookup returns. */
    arrive(lists: {[id: string]: FormSelectItem[]}): void {
      this.lists = lists;
      (this as any).refresh();
    }
  }

  let form: TestForm;

  const contextItemFields = (): FormProperty[] =>
    (form.delegatedProperties.context[0] as FormFieldSetProperty).properties;

  const shownProperty = (id: string): FormProperty =>
    [...form.rows, ...form.advancedRows].map(row => row.items).flat()
      .find(item => item.property.id === id)?.property;

  beforeEach(() => {
    const destroyRef = {onDestroy: () => () => {}} as unknown as DestroyRef;
    const cd = {markForCheck: () => {}, detectChanges: () => {}} as unknown as ChangeDetectorRef;
    form = new TestForm(new UntypedFormBuilder(), destroyRef, cd);
    form.properties = PROPERTIES;
  });

  const build = (layout: any = {}) => {
    form.layout = layout;
    form.ngOnChanges({properties: {}, layout: {}} as unknown as SimpleChanges);
    form.writeValue({script: 'return 1;', context: []});
  };

  it('renders a field as the type the layout names', () => {
    build({types: {script: FormPropertyType.textarea}});
    expect(shownProperty('script').type).toBe(FormPropertyType.textarea);
    // And still has a control, because a textarea is one of the types this form lays out itself.
    expect(form.form.get('script')).toBeTruthy();
  });

  it('ignores a type that would take a delegated field away from its own editor', () => {
    // An array rendered as a textarea would be given a control that cannot hold its rows, and the
    // first keystroke would replace them with a string.
    build({types: {context: FormPropertyType.textarea}});
    expect(shownProperty('context').type).toBe(FormPropertyType.array);
    expect(form.form.get('context')).toBeNull();
    expect(form.delegatedProperties.context).toBeTruthy();
  });

  it('ignores a type that would take the masking off a secret', () => {
    // `keep()` in the dialog decides what is a secret from the mapper's type, so a retyped password
    // is still treated as one: emptied and it is dropped rather than sent, which makes a plain box
    // that cannot be cleared and shows the value wherever the gateway returns it.
    form.properties = [property('privateKey', FormPropertyType.password)];
    build({types: {privateKey: FormPropertyType.textarea}});
    expect(shownProperty('privateKey').type).toBe(FormPropertyType.password);
  });

  it('carries a hidden field\'s value through an edit, rather than dropping it', () => {
    // What lets a layout hide a field and still have the dialog send it -- `SCRIPTING.DS` hides
    // `scriptPermissions` for the security reason and names it in `sendEmpty` because the gateway
    // answers an absent one with a 500. That only works because this component merges its rendered
    // controls *over* the value it was written, instead of emitting them alone.
    build();
    form.writeValue({script: 'return 1;', context: [], scriptPermissions: 'superadmin'});
    let emitted: any = null;
    form.registerOnChange((value: any) => emitted = value);
    // `scriptPermissions` has no control at all here -- it is not among this form's properties,
    // which is exactly the position a hidden field is in.
    form.form.get('script').setValue('return 2;');
    expect(emitted).withContext('the form emitted nothing').toBeTruthy();
    expect(emitted.scriptPermissions).toBe('superadmin');
    expect(emitted.script).toBe('return 2;');
  });

  it('leaves a field alone when the layout names no type for it', () => {
    build();
    expect(shownProperty('script').type).toBe(FormPropertyType.text);
  });

  it('puts the layout\'s hint on the field, over the one the device sent', () => {
    // The mapper fills `hint` from the schema's own `description`; a layout naming one has read that
    // description and decided against it. Where the layout says nothing the device's text stands.
    form.properties = [property('script', FormPropertyType.text, {hint: 'from the gateway'}),
      property('context', FormPropertyType.array,
        {arrayItemType: FormPropertyType.fieldset, properties: CONTEXT_ITEM_FIELDS})];
    build({hints: {script: 'ours'}});
    expect(shownProperty('script').hint).toBe('ours');
    form.properties = [property('script', FormPropertyType.text, {hint: 'from the gateway'})];
    build();
    expect(shownProperty('script').hint).toBe('from the gateway');
  });

  it('puts a looked-up list on a field inside a delegated array', () => {
    build();
    expect(contextItemFields()[0].type).toBe(FormPropertyType.text);
    form.arrive({'context.xid': POINTS});
    const xid = contextItemFields()[0];
    expect(xid.id).toBe('xid');
    expect(xid.type).toBe(FormPropertyType.select);
    expect((xid as any).items).toBe(POINTS);
    // Only that one field, and the array itself stays an array.
    expect(contextItemFields()[1].type).toBe(FormPropertyType.text);
    expect(form.delegatedProperties.context[0].type).toBe(FormPropertyType.array);
  });

  it('does not hand a delegated field the group that put it in Advanced', () => {
    // Found on screen, on `BACNET_MSTP.DS`: `alarmLevels` is an array, so it is delegated, and it
    // carries `group: 'Advanced'` because that is what lifts it into our panel. `tb-dynamic-form`
    // renders a group as a panel of its own titled after it, so the field arrived inside a second
    // "Advanced" panel nested in ours -- two identical headings, three cards deep with the array's.
    // The group has done its work by then; it must not travel on.
    form.properties = [property('alarmLevels', FormPropertyType.array,
      {group: GATEWAY_ADVANCED_GROUP, arrayItemType: FormPropertyType.fieldset,
        properties: CONTEXT_ITEM_FIELDS})];
    build();
    expect(form.advancedRows.length).toBe(1);
    expect(form.delegatedProperties.alarmLevels[0].group).toBeUndefined();
  });

  it('applies the list once, so the delegated form is not rebuilt on every pass', () => {
    // `tb-dynamic-form` builds its own form from the `properties` it is handed. A new array each
    // change-detection pass would rebuild it under the operator.
    build();
    form.arrive({'context.xid': POINTS});
    const applied = form.delegatedProperties.context;
    (form as any).layoutRows();
    (form as any).layoutRows();
    expect(form.delegatedProperties.context).toBe(applied);
  });

  it('makes a field the layout names required, and reports the form invalid', () => {
    // What the dialog's save gate reads. `MQTT.DS.brokerUri` is the case: the gateway answers an
    // absent or empty one with a 500 inside `validateURI`, so the form has to refuse it first.
    build({required: ['script']});
    expect(shownProperty('script').required).toBe(true);
    form.writeValue({script: '', context: []});
    expect(form.form.get('script').hasError('required')).toBe(true);
    expect(form.validate(null)).toEqual({gatewayForm: {valid: false}});
    form.writeValue({script: 'return 1;', context: []});
    expect(form.validate(null)).toBeNull();
  });

  it('gives a number the layout\'s floor, and never loosens the schema\'s', () => {
    // `validatorsFor` reads `min` off the property, so a layout only has to fill it in. Tightening
    // only: a layout must not be able to widen a range the device declared.
    form.properties = [property('port', FormPropertyType.number)];
    build({min: {port: 1}});
    expect(shownProperty('port').min).toBe(1);
    form.writeValue({port: 0});
    expect(form.form.get('port').hasError('min')).toBe(true);
    form.writeValue({port: 1});
    expect(form.validate(null)).toBeNull();
    form.properties = [property('port', FormPropertyType.number, {min: 1024})];
    build({min: {port: 1}});
    expect(shownProperty('port').min).toBe(1024);
  });

  it('keeps the list when the form is written to again', () => {
    // A second write rebuilds the delegated properties, which is where the list was written. Without
    // the applied-set being rebuilt with them, the select would fall back to a text box and nothing
    // would ever put it back -- the lookup has already returned.
    build();
    form.arrive({'context.xid': POINTS});
    form.writeValue({script: 'return 2;', context: [{xid: 'DP_1'}]});
    expect(contextItemFields()[0].type).toBe(FormPropertyType.select);
  });

  it('ignores a nested key naming a field the array does not have', () => {
    build();
    form.arrive({'context.nosuchfield': POINTS, 'nosucharray.xid': POINTS});
    expect(contextItemFields()).toBe(CONTEXT_ITEM_FIELDS);
  });

  it('keeps a top-level list and a nested one apart', () => {
    // A property id can never contain a dot -- the schema mapper's `PROPERTY_NAME` refuses one --
    // so the two key shapes cannot collide.
    build();
    form.arrive({script: POINTS, 'context.xid': POINTS});
    expect(shownProperty('script').type).toBe(FormPropertyType.select);
    expect(contextItemFields()[0].type).toBe(FormPropertyType.select);
  });

  it('carries a hidden field through the value it never renders', () => {
    // What makes hiding `META.PL.scriptPermissions` safe: the field keeps its value and is still
    // reported, so a save neither blanks a stored one nor sends a new one.
    let reported: any;
    build({hidden: ['script']});
    form.registerOnChange(value => reported = value);
    expect(shownProperty('script')).toBeUndefined();
    expect(form.form.get('script')).toBeNull();
    // Any field reporting a change propagates the whole value, hidden keys included.
    form.sliceChanged('context', {context: [{xid: 'DP_1'}]});
    expect(reported.script).toBe('return 1;');
    expect(reported.context).toEqual([{xid: 'DP_1'}]);
  });
  // --- the [disabled] input versus Angular's own setDisabledState ------------------------------

  it('keeps a read-only form read-only when Angular says the control is enabled', () => {
    // The order a dialog actually produces: the `[disabled]` input is applied, then `setUpControl`
    // calls `setDisabledState` with the *control's* state. Every dialog in this feature binds this
    // form with a standalone `[(ngModel)]`, whose own FormControl is always enabled, and
    // `setDisabledStateDefault` is 'always' -- so Angular says `false` straight after the input said
    // `true`. Taken literally that cancelled the input, and a read-only dialog rendered editable
    // fields on a type Cortex refuses to save.
    form.disabled = true;
    build();
    expect(form.form.disabled).toBe(true);

    form.setDisabledState(false);
    expect(form.form.disabled).withContext('Angular must not cancel the input').toBe(true);
    // And the input itself is not overwritten, which is what made `patch()` re-confirm the wrong
    // answer on every value change.
    expect(form.disabled).toBe(true);

    // A rebuild goes through the same applier, so it does not lose the state either.
    build();
    expect(form.form.disabled).toBe(true);
  });

  it('lets the control disable a form that has no disabled input', () => {
    // The `formControlName` shape, which is how TB's own callers bind a form like this: there is no
    // input to respect, so the control is the only source and must still work.
    build();
    expect(form.form.disabled).toBe(false);
    form.setDisabledState(true);
    expect(form.form.disabled).toBe(true);
    form.setDisabledState(false);
    expect(form.form.disabled).toBe(false);
  });

  it('re-disables the fields a layout locked when the form is enabled again', () => {
    // `enable()` knows nothing of individual fields, so the per-field pass has to run after it --
    // otherwise a layout's `readonly` would survive only until the first enable.
    build({readonly: ['script']});
    expect(form.form.get('script').disabled).toBe(true);
    form.setDisabledState(true);
    form.setDisabledState(false);
    expect(form.form.get('script').disabled)
      .withContext('a locked field must not come back editable').toBe(true);
  });

  /**
   * The mesh node point form, on the real layout, because its row comment used to describe an order
   * `pack` does not produce and a row that changed nothing.
   *
   * Property order and types are the published schema's: `dataType` is an accepted enum so it is a
   * select, `settable` is a boolean so it is a switch, and `type`'s enum is `toString()` garbage the
   * mapper refuses, so it degrades to text (D148).
   */
  it('lays a mesh node point out in schema order, with the switch on a line of its own', () => {
    form.properties = [
      property('dataType', FormPropertyType.select, {items: [{value: 'NUMERIC', label: 'NUMERIC'}]}),
      property('settable', FormPropertyType.switch),
      property('relinquishable', FormPropertyType.switch),
      property('configurationDescription', FormPropertyType.text),
      property('attributeId', FormPropertyType.number),
      property('type', FormPropertyType.text)
    ];
    form.layout = GATEWAY_FORM_LAYOUTS['VIRTUAL_MESH_NODE.PL'];
    form.ngOnChanges({properties: {}, layout: {}} as unknown as SimpleChanges);
    form.writeValue({dataType: 'NUMERIC', settable: true, attributeId: 88, type: 'BOOL'});

    expect(form.rows.map(row => row.items.map(item => item.property.id)))
      .toEqual([['dataType'], ['settable'], ['attributeId', 'type']]);
    // Which is the point of the correction: `settable` is second, not under the other three. A switch
    // is not in `PAIRABLE_TYPES`, so it flushes whatever was pending and takes its own row -- an
    // explicit `['dataType']` row would have bought nothing.
    expect(form.advancedRows).toEqual([]);
    expect(shownProperty('relinquishable')).toBeUndefined();
    expect(shownProperty('configurationDescription')).toBeUndefined();
  });
});
