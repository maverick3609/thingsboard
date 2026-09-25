// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input, OnChanges,
  SimpleChanges } from '@angular/core';
import { ControlValueAccessor, NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder,
  UntypedFormControl, UntypedFormGroup, ValidatorFn, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { coerceBoolean } from '@shared/decorators/coercion';
import { FormProperty, FormPropertyType } from '@shared/models/dynamic-form.models';
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

  private value: {[id: string]: any} = {};
  private shown: FormProperty[] = [];
  private propagateChange: (value: any) => void = () => {};

  constructor(private fb: UntypedFormBuilder,
              private destroyRef: DestroyRef,
              private cd: ChangeDetectorRef) {
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
    if (changes.disabled && !changes.disabled.firstChange) {
      this.setDisabledState(this.disabled);
    }
  }

  registerOnChange(fn: any): void {
    this.propagateChange = fn;
  }

  registerOnTouched(_fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.form.disable({emitEvent: false});
    } else {
      this.form.enable({emitEvent: false});
    }
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
    this.shown.filter(property => !RENDERED_TYPES.includes(property.type)).forEach(property => {
      this.slices[property.id] = {[property.id]: this.value[property.id]};
      this.delegatedProperties[property.id] = [property];
    });
    this.layoutRows();
    this.setDisabledState(this.disabled);
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
    return items && this.narrowable(property)
      ? {...property, type: FormPropertyType.select, items} : {...property};
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

  private gatedProperty(property: FormProperty): FormProperty {
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
  private layoutRows(): void {
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
