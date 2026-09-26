// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, DestroyRef, forwardRef, Input, OnInit } from '@angular/core';
import { NG_VALIDATORS, NG_VALUE_ACCESSOR, UntypedFormBuilder } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { FormSelectItem } from '@shared/models/dynamic-form.models';
import { GatewayFormComponent } from './gateway-form.component';

/**
 * The property every BACnet point reads unless told otherwise, and the one the defaults name.
 *
 * Used as the substitute when a change of object type leaves the held property unavailable — see
 * {@link BacnetPointFormComponent.applyProperties}.
 */
const PRESENT_VALUE = 'present-value';

/**
 * How the five data types read on screen.
 *
 * The schema declares them as an enum, so the mapper already labels them this way; the list is
 * repeated here because narrowing the field at runtime replaces the mapper's items outright, and
 * an item carries its own label. `DataTypes` in the gateway's core declares exactly these five.
 */
const BACNET_DATA_TYPE_LABELS: {[value: string]: string} = {
  BINARY: 'Binary',
  MULTISTATE: 'Multistate',
  NUMERIC: 'Numeric',
  ALPHANUMERIC: 'Alphanumeric',
  IMAGE: 'Image'
};

/**
 * A BACnet point's form: the generic one plus the two lists only the gateway can answer.
 *
 * `objectTypeId` and `propertyIdentifierId` are both declared bare strings, and the second depends
 * on the first — the properties of an analog input are not the properties of a schedule. Neither
 * can be a layout constant, so the type gets a component; `GATEWAY_FORM_LAYOUTS['BACNET_IP.PL']`
 * still carries everything declarative and nothing here repeats it.
 *
 * The third list is the reason this is worth more than two dropdowns.
 * `/v2/bacnet/object-properties/{type}` reports, per property, the data types it can be read as —
 * which is the same list `BACnetDataSourceDefinition.validate` checks the point's data type
 * against. Offering exactly it makes that rejection unreachable from the form, where the gateway's
 * own UI offers all five for every property and leaves the operator to find out on save.
 */
@Component({
  selector: 'tb-bacnet-point-form',
  templateUrl: './gateway-form.component.html',
  styleUrls: ['./gateway-form.component.scss'],
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => BacnetPointFormComponent),
      multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => BacnetPointFormComponent), multi: true}
  ],
  standalone: false
})
export class BacnetPointFormComponent extends GatewayFormComponent implements OnInit {

  /** The gateway to ask. Without it every field stays the text box it was. */
  @Input() deviceId: string;

  private objectTypes: FormSelectItem[] = [];
  private propertyItems: FormSelectItem[] = [];
  /** Data types per property name, as the gateway reports them. Prototype-less: keys are its. */
  private supported: {[propertyName: string]: string[]} = Object.create(null);
  /** The object type `propertyItems` belongs to, so a value change refetches only when it must. */
  private loadedFor: string = null;

  constructor(fb: UntypedFormBuilder,
              destroyRef: DestroyRef,
              cd: ChangeDetectorRef,
              private gatewayService: InferrixGatewayService) {
    super(fb, destroyRef, cd);
    // `patch` writes the model with `emitEvent: false`, so this fires only on an operator's edit.
    // The value the form opens on is picked up by `syncProperties` after the type list arrives.
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.syncProperties());
  }

  ngOnInit(): void {
    if (!this.deviceId) {
      return;
    }
    this.gatewayService.getBacnetObjectTypes(this.deviceId,
      {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: types => {
          this.objectTypes = (types ?? [])
            .filter(type => !!type.typeName)
            .map(type => ({value: type.typeName, label: type.translation || type.typeName}));
          if (!this.objectTypes.length) {
            return;
          }
          this.refresh();
          this.syncProperties();
        },
        // Left as free text, which is what these fields were before this component existed.
        error: () => {}
      });
  }

  protected override runtimeOptions(): {[id: string]: FormSelectItem[]} {
    const options: {[id: string]: FormSelectItem[]} = Object.create(null);
    if (this.objectTypes.length) {
      options.objectTypeId = this.objectTypes;
    }
    if (this.propertyItems.length) {
      options.propertyIdentifierId = this.propertyItems;
    }
    const types = this.supportedFor(this.form.get('propertyIdentifierId')?.value);
    if (types?.length) {
      options.dataType = types.map(value => ({value, label: BACNET_DATA_TYPE_LABELS[value] ?? value}));
    }
    return options;
  }

  /** Reloads the property list when the object type has changed, and not otherwise. */
  private syncProperties(): void {
    const objectType = this.form.get('objectTypeId')?.value;
    if (!objectType || objectType === this.loadedFor) {
      this.clearIllegalDataType();
      return;
    }
    this.loadedFor = objectType;
    this.gatewayService.getBacnetObjectProperties(this.deviceId, objectType,
      {ignoreLoading: true, ignoreErrors: true})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        // Guarded against its own answer arriving late. Two quick changes of object type leave two
        // requests in flight, and the gateway is on the other side of a LAN or a VPN -- without
        // this the slower one wins and the form offers the properties of a type it is no longer
        // showing, which is a point the gateway then refuses.
        next: answer => {
          if (objectType === this.loadedFor) {
            this.applyProperties(answer?.properties ?? []);
          }
        },
        error: () => {
          if (objectType === this.loadedFor) {
            this.applyProperties([]);
          }
        }
      });
  }

  private applyProperties(properties: {propertyName?: string; supportedDataTypes?: string[]}[]):
    void {
    const named = properties.filter(property => !!property.propertyName);
    // Sorted here, not by the gateway: it answers in the order BACnet4J declares them, which puts
    // `present-value` twenty-odd rows down a list of forty.
    this.propertyItems = named
      .map(property => ({value: property.propertyName, label: property.propertyName}))
      .sort((left, right) => left.label.localeCompare(right.label));
    this.supported = Object.create(null);
    named.forEach(property => {
      this.supported[property.propertyName] = property.supportedDataTypes ?? [];
    });

    // A property the new object type does not have is replaced rather than cleared. Cleared is
    // what a Modbus data type gets, because an empty one there is a validation message -- here
    // `BACnetPointLocatorModel.toVO` calls `PropertyIdentifier.forName(null)` before anything
    // validates, so an empty one is a 500 from the gateway and no message at all.
    const held = this.form.get('propertyIdentifierId');
    if (held && this.propertyItems.length
      && !this.propertyItems.some(item => item.value === held.value)) {
      const fallback = this.propertyItems.some(item => item.value === PRESENT_VALUE)
        ? PRESENT_VALUE : this.propertyItems[0].value;
      // Emitted, not silent: this runs from an HTTP callback rather than from inside the form's
      // own change handler, and that handler is what copies a control back into the value object
      // the dialog saves. A silent write would show the substitution and not send it.
      held.setValue(fallback);
    }
    this.clearIllegalDataType();
    this.refresh();
  }

  /**
   * Drops a data type the chosen property cannot be read as.
   *
   * Done here rather than by the shared gate-clearing, which only knows about a layout's own
   * `gatedOptions`: this list is fetched, and a blanket rule over every runtime list would wipe a
   * virtual point's attraction target whenever the gateway answered with a page that did not
   * happen to include it.
   */
  private clearIllegalDataType(): void {
    const control = this.form.get('dataType');
    const types = this.supportedFor(this.form.get('propertyIdentifierId')?.value);
    if (!control || !types?.length || control.value === null || control.value === undefined) {
      return;
    }
    if (!types.includes(control.value)) {
      control.setValue(null);
    }
  }

  private supportedFor(propertyName: any): string[] | undefined {
    return typeof propertyName === 'string'
      && Object.prototype.hasOwnProperty.call(this.supported, propertyName)
      ? this.supported[propertyName] : undefined;
  }
}
