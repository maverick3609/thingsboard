// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input, OnChanges,
  SimpleChanges } from '@angular/core';
import { ControlValueAccessor, NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder,
  UntypedFormControl, UntypedFormGroup, ValidatorFn, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { coerceBoolean } from '@shared/decorators/coercion';
import { FormFieldSetProperty, FormProperty, FormPropertyType,
  FormSelectItem } from '@shared/models/dynamic-form.models';
import { GATEWAY_ADVANCED_GROUP } from '@shared/models/inferrix-gateway-schema.models';
import { GatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';

/**
 * Field types this component lays out itself. Everything else -- an array, a nested object, a
 * date, an image -- is handed to `tb-dynamic-form`, whose editors for those are the ones the rest
 * of the product uses and are not worth a second implementation.
 */
const RENDERED_TYPES = [FormPropertyType.text, FormPropertyType.password, FormPropertyType.number,
  FormPropertyType.select, FormPropertyType.textarea, FormPropertyType.switch];

/**
 * Types that read as a labelled box and so can sit two to a row. A toggle carries its own label to
 * its right and a textarea is tall; both take a row of their own, as they do in ThingsBoard's own
 * entity forms.
 */
const PAIRABLE_TYPES = [FormPropertyType.text, FormPropertyType.password, FormPropertyType.number,
  FormPropertyType.select];

/**
 * A table entry, or undefined.
 *
 * Read through `hasOwnProperty` rather than indexed directly. The mapper's `PROPERTY_NAME` admits
 * `constructor` and `toString` -- they are legal Java field names -- and a plain object literal
 * answers both from its prototype with a function. A gateway declaring a property called
 * `constructor` would otherwise have `options.constructor` return `Object`, which is assigned as
 * the field's option list and throws inside the renderer rather than being ignored.
 */
const own = <T>(table: {[key: string]: T} | undefined, key: string): T | undefined =>
  table && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;

/** What {@link GatewayFormComponent.runtimeOptions} answers when a form looks nothing up. */
const NO_RUNTIME_OPTIONS: {[id: string]: FormSelectItem[]} = Object.create(null);

/** Fields on one line. One or two when they pair; always one when they do not. */
export interface GatewayFormRow {
  items: GatewayFormItem[];
}

export interface GatewayFormItem {
  property: FormProperty;
  /** Rendered by `tb-dynamic-form` rather than by this template. */
  delegated: boolean;
}

/**
 * A gateway model's form, laid out the way ThingsBoard lays out an entity form.
 *
 * `tb-dynamic-form` renders the same properties as a *widget settings panel*: one field per row,
 * label beside or above its control, sections as `tb-settings` expansion panels. That is correct
 * where it is used and wrong here -- a data source is an entity being edited, and every other
 * entity form in the product pairs its fields two to a row inside `tb-form-row tb-standard-fields`
 * and puts its optional settings behind a `configuration-panel`. This does that, and hands the
 * field types it does not lay out back to `tb-dynamic-form` so there is one implementation of the
 * array editor, the nested-object form and the rest.
 *
 * The properties still come from {@link schemaToFormProperties}, which stays the security boundary:
 * a {@link GatewayFormLayout} may only hide a field, narrow a free string to a fixed option list,
 * or move a field into Advanced. Nothing a gateway sends becomes markup or code by passing through
 * here.
 */
@Component({
  selector: 'tb-gateway-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => GatewayFormComponent), multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => GatewayFormComponent), multi: true}
  ],
  standalone: false
})
export class GatewayFormComponent implements ControlValueAccessor, OnChanges {

  FormPropertyType = FormPropertyType;

  @Input() properties: FormProperty[];
  @Input() layout: GatewayFormLayout;
  @Input() title: string;
  @Input() disabled: boolean;
  /** What Angular's {@link setDisabledState} last said, kept out of {@link disabled}. */
  private disabledByControl = false;

  /** Draws the panel's own border, for a section nested inside another form. */
  @Input() @coerceBoolean() stroked = false;

  form: UntypedFormGroup;
  rows: GatewayFormRow[] = [];
  advancedRows: GatewayFormRow[] = [];
  advancedTitle = GATEWAY_ADVANCED_GROUP;

  /**
   * One `{[id]: value}` object per delegated property, kept as a stable reference.
   *
   * `tb-dynamic-form` takes the whole value object for the properties it was given, so a delegated
   * field is given a form of one. The object has to be held rather than built in the template: a
   * fresh object every change-detection pass would have `ngModel` write, emit and write again.
   */
  slices: {[id: string]: {[id: string]: any}} = Object.create(null);

  /** The same, for the `properties` input: a new array each pass would reset the form it built. */
  delegatedProperties: {[id: string]: FormProperty[]} = Object.create(null);

  /** Which looked-up list is already on which nested field. See {@link applyNestedOptions}. */
  private nestedApplied: {[key: string]: FormSelectItem[]} = Object.create(null);

  protected value: {[id: string]: any} = {};
  protected shown: FormProperty[] = [];
  private propagateChange: (value: any) => void = () => {};

  constructor(private fb: UntypedFormBuilder,
              protected destroyRef: DestroyRef,
              protected cd: ChangeDetectorRef) {
    this.form = this.fb.group({});
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.value = {...this.value, ...this.form.getRawValue()};
      this.clearIllegalGatedValues();
      this.layoutRows();
      this.propagateChange(this.value);
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.properties || changes.layout) {
      this.build();
    }
    if (changes.disabled) {
      this.applyDisabled();
    }
  }

  registerOnChange(fn: any): void {
    this.propagateChange = fn;
  }

  registerOnTouched(_fn: any): void {
  }

  /**
   * Angular's call, which is not a statement about this form.
   *
   * `setUpControl` calls it once at setup with the **control's** disabled state, and
   * `setDisabledStateDefault` is `'always'` (`@angular/forms` 20.3.28, `forms.mjs:3188` and
   * `:3211-3213`) with no `CALL_SET_DISABLED_STATE` provider anywhere in this app. Every dialog here
   * binds this form with a standalone `[(ngModel)]`, whose own `FormControl` is always enabled -- so
   * Angular says `false` a moment after the `[disabled]` input said `true`.
   *
   * Assigning that onto `this.disabled` is what the first version did, and it cancelled the input:
   * `ngOnChanges` could not undo it because `firstChange` was true, and `patch()` then re-confirmed
   * the clobbered value. A read-only dialog rendered every field the layout had not separately
   * marked `readonly` as editable. Save was still gone -- `@if (!data.readonly)` -- so nothing could
   * be written, but the form said the opposite of what the dialog said, which on a type Cortex
   * refuses to save is the whole of the message.
   *
   * So the two are kept apart and the stricter wins. A form bound by `formControlName` instead has
   * no `disabled` input and is governed by its control, which is what TB's own callers expect.
   */
  setDisabledState(isDisabled: boolean): void {
    this.disabledByControl = isDisabled;
    this.applyDisabled();
  }

  /** Disabled if either side says so. Neither one overwrites the other's answer. */
  private applyDisabled(): void {
    if (this.disabled || this.disabledByControl) {
      this.form.disable({emitEvent: false});
      return;
    }
    this.form.enable({emitEvent: false});
    // `enable()` knows nothing of the individual fields, so they go back afterwards. A disabled
    // control is left out of `form.value` but not of `getRawValue`, which is what the change
    // subscription reads -- so a field the operator cannot edit still round-trips on a save.
    this.shown.filter(property => property.disabled)
      .forEach(property => this.form.get(property.id)?.disable({emitEvent: false}));
  }

  writeValue(value: {[id: string]: any}): void {
    this.value = value ?? {};
    this.patch();
  }

  validate(_control: UntypedFormControl) {
    return this.form.valid ? null : {gatewayForm: {valid: false}};
  }

  /** A delegated field reported a new value. Its slice is the form it was given. */
  sliceChanged(id: string, slice: {[id: string]: any}): void {
    this.slices[id] = slice ?? {};
    this.value = {...this.value, [id]: this.slices[id][id]};
    this.propagateChange(this.value);
  }

  /**
   * A row is identified by the fields in it, not by its position.
   *
   * Changing a virtual point's change type replaces the rows below the type row, and by index the
   * row that held `maxChange` becomes the row that holds `roll` -- the same DOM reused for a
   * different control. Keying on the contents destroys and rebuilds instead.
   */
  trackRow(_index: number, row: GatewayFormRow): string {
    return row.items.map(item => item.property.id).join('|');
  }

  trackItem(_index: number, item: GatewayFormItem): string {
    return item.property.id;
  }

  /**
   * Builds the controls this form owns.
   *
   * Done once per set of properties rather than per layout pass: rebuilding on every keystroke
   * that changes a gate would take the focused control out from under the operator.
   */
  private build(): void {
    Object.keys(this.form.controls).forEach(id => this.form.removeControl(id, {emitEvent: false}));
    // Prototype-less, for the reason in `own`: these are read from the template by a property id
    // the gateway chose, and `{}` would answer `constructor` with a function.
    this.slices = Object.create(null);
    this.delegatedProperties = Object.create(null);
    const hidden = new Set(this.layout?.hidden ?? []);
    this.shown = (this.properties ?? []).filter(property => !hidden.has(property.id))
      .map(property => this.withLayout(property));
    this.shown.filter(property => RENDERED_TYPES.includes(property.type)).forEach(property =>
      this.form.addControl(property.id, this.fb.control(null, this.validatorsFor(property)),
        {emitEvent: false}));
    this.rows = [];
    this.advancedRows = [];
    this.patch();
  }

  private patch(): void {
    if (!this.shown.length) {
      return;
    }
    this.form.patchValue(this.value, {emitEvent: false});
    // Rebuilt together with the delegated properties they were written onto, and re-applied by the
    // `layoutRows` below. Kept here rather than in `build` because this is where those properties
    // are replaced: a second write would otherwise leave a looked-up list recorded as applied and
    // the plain field on screen, with nothing left to trigger it again.
    this.nestedApplied = Object.create(null);
    this.shown.filter(property => !RENDERED_TYPES.includes(property.type)).forEach(property => {
      this.slices[property.id] = {[property.id]: this.value[property.id]};
      this.delegatedProperties[property.id] = [property];
    });
    this.layoutRows();
    this.applyDisabled();
    this.cd.markForCheck();
  }

  private validatorsFor(property: FormProperty): ValidatorFn[] {
    const validators: ValidatorFn[] = property.required ? [Validators.required] : [];
    if (property.type === FormPropertyType.number) {
      if (typeof property.min === 'number') {
        validators.push(Validators.min(property.min));
      }
      if (typeof property.max === 'number') {
        validators.push(Validators.max(property.max));
      }
    }
    return validators;
  }

  /**
   * The property as the layout describes it: a fixed option list in place of a free string, or an
   * option list chosen by another control.
   *
   * A gated field is re-read on every layout pass, because the gate is a control the operator can
   * change. An ungated one never changes and is settled here.
   */
  private withLayout(property: FormProperty): FormProperty {
    const items = own(this.layout?.options, property.id);
    // `disabled` is already how the mapper marks a `readOnly` field, so a layout naming one adds
    // to that set rather than introducing a second way of saying it.
    const disabled = property.disabled || (this.layout?.readonly ?? []).includes(property.id);
    // Adds to the schema's own `required`, never clears it.
    const required = property.required || (this.layout?.required ?? []).includes(property.id);
    // A layout hint wins over the device's own description, because naming one is a decision taken
    // with that description in view. Left alone where the layout says nothing.
    const hint = own(this.layout?.hints, property.id) ?? property.hint;
    // Same rule as `required`: tightens what the schema declared, never loosens it. `validatorsFor`
    // reads `min` off the property, so filling it in here is all a layout has to do.
    const min = own(this.layout?.min, property.id);
    const retyped = {...property, disabled, required, hint,
      min: typeof min === 'number' ? Math.max(min, property.min ?? min) : property.min,
      type: this.laidOutType(property)};
    return items && this.narrowable(property)
      ? {...retyped, type: FormPropertyType.select, items} : retyped;
  }

  /**
   * The type the layout asks for, where it asks for one this component can lay out.
   *
   * Only ever a move between the types rendered here -- a `text` that is really a script body
   * becoming a `textarea`. Refusing anything else is what keeps a layout from replacing a delegated
   * field's editor with a control that cannot hold its value: `build` types the control from what
   * this returns, so an array turned into a textarea would lose its rows on the first keystroke.
   *
   * A `password` is refused for the second reason: the dialog's `keep()` reads secrecy off the
   * *mapper's* type, not off this one, so a layout that retyped a secret would paint it into a
   * plain box that still behaves like a secret -- shown on screen wherever the gateway returns a
   * value, and silently unclearable, because an emptied secret is dropped rather than sent. The
   * layouts may not name `password` either; that direction is a spec, this one is here because a
   * spec cannot see which fields the schema marked `writeOnly`.
   */
  private laidOutType(property: FormProperty): FormPropertyType {
    const type = own(this.layout?.types, property.id);
    return type && RENDERED_TYPES.includes(type) && RENDERED_TYPES.includes(property.type)
      && property.type !== FormPropertyType.password ? type : property.type;
  }

  /**
   * Whether an option list may be put on this property at all.
   *
   * Only a field this component lays out itself can become a select. `build` decides which
   * properties get a control from the type the schema gave, so narrowing a delegated one -- an
   * array, a nested object -- would ask the template for a `formControlName` that was never
   * created. A layout naming one is a mistake in the layout; it is inert rather than fatal.
   */
  private narrowable(property: FormProperty): boolean {
    return RENDERED_TYPES.includes(property.type);
  }

  /**
   * Option lists a subclass has fetched from the gateway, by property id.
   *
   * The generic renderer has none: a layout is a constant, and a list that has to be *looked up* is
   * the one thing it cannot describe. `VIRTUAL.PL.attractionPointXid` is the first -- the choices
   * are every numeric point on the gateway, which is an HTTP call, so the type gets a component.
   *
   * A key of the form `array.field` names a field *inside* a delegated array's rows instead --
   * `META.PL`'s `context.xid` is another point on the gateway, one per context entry. The two cannot
   * be confused: the mapper's `PROPERTY_NAME` admits no dot, so no top-level property can be called
   * `context.xid`.
   *
   * Consulted on every layout pass rather than when controls are built, so a list arriving after
   * the form is on screen turns the field from a text box into a select without disturbing what the
   * operator has already typed elsewhere.
   */
  protected runtimeOptions(): {[id: string]: FormSelectItem[]} {
    return NO_RUNTIME_OPTIONS;
  }

  /** Re-reads {@link runtimeOptions}. A subclass calls this when a lookup returns. */
  protected refresh(): void {
    this.layoutRows();
    this.cd.markForCheck();
  }

  /**
   * Puts a looked-up option list on a field inside a delegated array's rows.
   *
   * `tb-dynamic-form` builds each row of an array of objects by cloning the *array* property and
   * swapping its type for `arrayItemType` (`toPropertyGroups`), so the row's fields are the
   * `properties` carried on the array itself. Rewriting one there reaches every row, including rows
   * the operator adds afterwards, without this component knowing anything about how they are drawn.
   *
   * Guarded on the list's identity rather than redone each pass. `delegatedProperties[id]` has to
   * keep the same reference between change-detection passes -- a new array rebuilds the form inside
   * it -- so a subclass holding its list in one object, as they all do, applies this exactly once.
   */
  private applyNestedOptions(): void {
    Object.entries(this.runtimeOptions()).forEach(([key, items]) => {
      const dot = key.indexOf('.');
      if (dot < 0 || this.nestedApplied[key] === items) {
        return;
      }
      const array = this.delegatedProperties[key.slice(0, dot)]?.[0] as FormFieldSetProperty;
      const nested = array?.properties;
      const target = nested?.find(child => child.id === key.slice(dot + 1));
      if (!target || !this.narrowable(target)) {
        return;
      }
      this.nestedApplied[key] = items;
      this.delegatedProperties[key.slice(0, dot)] = [{...array, properties: nested.map(child =>
        child === target ? {...child, type: FormPropertyType.select, items} : child)}];
    });
  }

  private gatedProperty(property: FormProperty): FormProperty {
    const runtime = own(this.runtimeOptions(), property.id);
    if (runtime && this.narrowable(property)) {
      return {...property, type: FormPropertyType.select, items: runtime};
    }
    const gate = own(this.layout?.gatedOptions, property.id);
    if (!gate || !this.narrowable(property)) {
      return property;
    }
    const items = own(gate.table, String(this.form.get(gate.by)?.value));
    return items ? {...property, type: FormPropertyType.select, items}
      : {...property, type: gate.unlisted ?? FormPropertyType.select, items: []};
  }

  /**
   * Drops a gated value the gate no longer admits.
   *
   * Changing a virtual point from BINARY to NUMERIC leaves `changeType` holding
   * `ALTERNATE_BOOLEAN`, which the device rejects. The select would show it as nothing selected
   * while still submitting it, so it is cleared rather than left to look empty.
   */
  private clearIllegalGatedValues(): void {
    Object.entries(this.layout?.gatedOptions ?? {}).forEach(([id, gate]) => {
      const control = this.form.get(id);
      const items = own(gate.table, String(this.form.get(gate.by)?.value));
      // No list for this gate value means the field is not an enum right now, not that whatever it
      // holds is illegal. Clearing here instead would wipe a virtual point's `startValue` the
      // moment anything else on the form was touched, since it is free text for every data type
      // but BINARY.
      if (!control || !items || control.value === null || control.value === undefined) {
        return;
      }
      if (!items.some(item => item.value === control.value)) {
        control.setValue(null, {emitEvent: false});
        this.value = {...this.value, [id]: null};
      }
    });
  }

  private visible(property: FormProperty): boolean {
    const rule = own(this.layout?.visibleWhen, property.id);
    return !rule || rule.values.includes(this.form.get(rule.by)?.value);
  }

  /**
   * Packs the visible fields into rows.
   *
   * Explicit rows first, then everything left over in schema order, pairing fields that read as a
   * labelled box and giving a row of its own to anything that does not. Visibility is applied
   * before pairing, so a hidden field does not leave a gap beside its neighbour.
   */
  protected layoutRows(): void {
    this.applyNestedOptions();
    const advanced = new Set([...(this.layout?.advanced ?? []),
      ...this.shown.filter(property => property.group === GATEWAY_ADVANCED_GROUP)
        .map(property => property.id)]);
    const items = this.shown.filter(property => this.visible(property))
      .map(property => ({property: this.gatedProperty(property),
        delegated: !RENDERED_TYPES.includes(property.type)}));
    this.rows = this.pack(items.filter(item => !advanced.has(item.property.id)));
    this.advancedRows = this.pack(items.filter(item => advanced.has(item.property.id)));
  }

  /**
   * An explicit row sits where its first field would have fallen anyway, rather than being lifted
   * to the top of the form. A layout that pairs `min` with `max` is saying they belong beside each
   * other, not that they come before everything else.
   */
  private pack(items: GatewayFormItem[]): GatewayFormRow[] {
    const byId = new Map(items.map(item => [item.property.id, item]));
    const openers = new Map<string, string[]>();
    const claimed = new Set<string>();
    (this.layout?.rows ?? []).forEach(ids => {
      const present = ids.filter(id => byId.has(id) && !claimed.has(id));
      if (!present.length) {
        return;
      }
      openers.set(present[0], present);
      present.forEach(id => claimed.add(id));
    });

    const rows: GatewayFormRow[] = [];
    let pending: GatewayFormItem | null = null;
    const flush = () => {
      if (pending) {
        rows.push({items: [pending]});
        pending = null;
      }
    };
    items.forEach(item => {
      const id = item.property.id;
      if (openers.has(id)) {
        flush();
        rows.push({items: openers.get(id).map(other => byId.get(other))});
      } else if (claimed.has(id)) {
        return;
      } else if (item.delegated || !PAIRABLE_TYPES.includes(item.property.type)) {
        flush();
        rows.push({items: [item]});
      } else if (pending) {
        rows.push({items: [pending, item]});
        pending = null;
      } else {
        pending = item;
      }
    });
    flush();
    return rows;
  }
}
