// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { FormProperty, FormPropertyType } from '@shared/models/dynamic-form.models';
import { GatewayRecipient } from '@shared/models/inferrix-gateway-event.models';

export interface GatewayModelDialogData {
  title: string;
  /** The model as the gateway serialised it, or a bare `{modelType}` when adding. */
  model: any;
  /** The model's own fields, mapped from the gateway's schema. */
  properties: FormProperty[];
  /** A data point's nested locator, whose concrete type follows its data source's protocol. */
  locatorProperties?: FormProperty[];
  locatorTitle?: string;
  readonly: boolean;
  /** Shown instead of a locator form when the gateway published no schema for that protocol. */
  locatorMissing?: boolean;
  /**
   * Fields holding a recipient list, edited by {@link GatewayRecipientsComponent} instead of by
   * the schema form. The gateway ships no subtype schemas for `RecipientEntryModel`, so a
   * schema-built form would render the discriminator and erase the addresses on save.
   */
  recipientFields?: string[];
  /** Fields carried through a save untouched, with a note saying so. */
  carriedFields?: {id: string; note: string}[];
}

/**
 * One gateway model, edited through the form its own schema describes.
 *
 * Nothing about the protocol is known here. The gateway publishes an OpenAPI schema per model type
 * and the mapper turns it into ThingsBoard form properties, so a Modbus data source and a BACnet
 * one are the same component with different inputs — which is the whole reason this feature can
 * cover protocol modules nobody has written yet.
 *
 * `xid` and `name` are edited here rather than by the schema form. They are in the schema because
 * they are in the model, but the xid is the identifier every point, event detector and publisher on
 * the device refers to this row by: it is frozen once the row exists, because changing it would
 * orphan all of them rather than rename anything.
 */
@Component({
  selector: 'tb-gateway-model-dialog',
  templateUrl: './gateway-model-dialog.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayModelDialogComponent
  extends DialogComponent<GatewayModelDialogComponent, any> {

  readonly isAdd: boolean;

  identityForm: UntypedFormGroup;
  values: {[id: string]: any};
  locatorValues: {[id: string]: any};
  recipients: {[id: string]: GatewayRecipient[]} = {};

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: GatewayModelDialogData,
              public dialogRef: MatDialogRef<GatewayModelDialogComponent, any>,
              private fb: UntypedFormBuilder) {
    super(store, router, dialogRef);
    this.isAdd = !data.model?.xid;
    this.identityForm = this.fb.group({
      name: [data.model?.name ?? '', [Validators.required, Validators.maxLength(255)]],
      // The stack's own xid shape. Left blank on an add, which makes the gateway generate one --
      // its generated xids are what every other module on the device already expects.
      xid: [{value: data.model?.xid ?? '', disabled: !this.isAdd},
        [Validators.maxLength(64), Validators.pattern(/^[A-Za-z0-9_.-]*$/)]]
    });
    if (data.readonly) {
      this.identityForm.disable();
    }
    this.values = this.pick(data.model, data.properties);
    this.locatorValues = this.pick(data.model?.pointLocator, data.locatorProperties);
    (data.recipientFields ?? []).forEach(field => {
      const existing = data.model?.[field];
      this.recipients[field] = Array.isArray(existing) ? [...existing] : [];
    });
  }

  recipientsChanged(field: string, recipients: GatewayRecipient[]): void {
    this.recipients[field] = recipients;
  }

  save(): void {
    if (this.identityForm.invalid) {
      this.identityForm.markAllAsTouched();
      return;
    }
    const identity = this.identityForm.getRawValue();
    // Spread the original first so everything the form does not render -- modelType, the surrogate
    // id, and any field a newer gateway added that this schema mapper skipped -- survives the
    // round trip. A save that sent only the rendered fields would silently reset the rest.
    const saved: any = {...this.data.model, ...this.keep(this.values, this.data.properties),
      name: identity.name};
    if (identity.xid) {
      saved.xid = identity.xid;
    }
    if (this.data.locatorProperties?.length) {
      saved.pointLocator = {...(this.data.model?.pointLocator ?? {}),
        ...this.keep(this.locatorValues, this.data.locatorProperties)};
    }
    (this.data.recipientFields ?? []).forEach(field => {
      saved[field] = this.recipients[field] ?? [];
    });
    this.dialogRef.close(saved);
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  /**
   * The form's values, minus the secrets the operator did not type into.
   *
   * A `writeOnly` field -- an MQTT broker password, an OPC password, a private key -- is accepted by
   * the gateway and never returned by it. So the form always starts empty for one, whether or not a
   * secret is stored, and there is no way to tell the two apart from here.
   *
   * Sending that empty value would **erase the stored credential** and take the data source offline
   * on the next poll, with nothing in the UI suggesting that is what happened. Empty therefore has
   * to mean "unchanged": the key is dropped from the payload entirely rather than sent as `''`, and
   * `save` spreads the original model underneath, so whatever the gateway already holds stays.
   *
   * Only password-typed properties are treated this way. An ordinary text field cleared on purpose
   * is a real edit and must reach the gateway as one.
   */
  private keep(values: {[id: string]: any}, properties: FormProperty[]): {[id: string]: any} {
    const secrets = new Set((properties ?? [])
      .filter(property => property.type === FormPropertyType.password)
      .map(property => property.id));
    const kept: {[id: string]: any} = {};
    Object.keys(values ?? {}).forEach(id => {
      const value = values[id];
      if (secrets.has(id) && (value === null || value === undefined || value === '')) {
        return;
      }
      kept[id] = value;
    });
    return kept;
  }

  /**
   * Only the fields the form will actually render.
   *
   * Passing the whole model through would put keys the form knows nothing about into its value
   * object, and the renderer writes its own value back wholesale — so they would come out again as
   * form state rather than as the model's, and `save` merges the model first precisely so they do
   * not have to.
   */
  private pick(source: any, properties: FormProperty[]): {[id: string]: any} {
    const values: {[id: string]: any} = {};
    (properties ?? []).forEach(property => {
      if (source && source[property.id] !== undefined) {
        values[property.id] = source[property.id];
      }
    });
    return values;
  }
}
