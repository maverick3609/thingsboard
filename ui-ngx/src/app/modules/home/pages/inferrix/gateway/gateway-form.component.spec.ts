// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, DestroyRef, SimpleChanges } from '@angular/core';
import { UntypedFormBuilder } from '@angular/forms';
import { FormFieldSetProperty, FormProperty, FormPropertyType,
  FormSelectItem } from '@shared/models/dynamic-form.models';
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

  it('leaves a field alone when the layout names no type for it', () => {
    build();
    expect(shownProperty('script').type).toBe(FormPropertyType.text);
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
});
