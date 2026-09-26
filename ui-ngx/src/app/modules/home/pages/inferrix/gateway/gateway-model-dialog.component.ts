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
import { GatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';


/**
 * A table of child rows rendered under the model's own form.
 *
 * This is what replaces the points tab. A data source's points and a publisher's published points
 * both arrive with their parent and are meaningless without it, so they are edited where they
 * live rather than in a flat list the operator has to filter back down to one parent.
 *
 * **Children are written the moment they are edited, not when the parent form is saved.** The
 * gateway has no route that saves a parent and its children together -- points go to their own
 * endpoint, and a parent save that carried them would have them ignored. So `rows` is owned by the
 * caller and mutated in place as each child write returns, and the dialog says as much on screen:
 * cancelling the parent form does not undo a point that was already changed.
 */
export interface GatewayModelChildren {
  title: string;
  /** The rows on screen. Owned and kept current by the component that opened this dialog. */
  rows: any[];
  /** What a row's second column shows -- the point it reads, or its data type -- and its header. */
  detail: (row: any) => string;
  detailHeader: string;
  readonly: boolean;
  /** Shown instead of the table until the parent exists, since a child needs its parent's xid. */
  needsSaveFirst: boolean;
  /**
   * The gateway creates these rows, so no Add button. Editing, toggling and deleting stay: what
   * goes is the one action whose form would open with nothing in it to fill in.
   */
  provisioned?: boolean;
  add: () => void;
  edit: (row: any) => void;
  delete: (row: any) => void;
  toggle: (row: any, enabled: boolean) => void;
  /**
   * Reads one row's live value, when the rows have one.
   *
   * Set for a data source's points and left unset for a publisher's, which have no value of their
   * own. The column appears only when this does: the gateway has no bulk point-value endpoint, so
   * a column that filled itself would be one request per visible row against a small edge box.
   */
  readValue?: (row: any) => void;
  /** What the value column shows for a row that has been read. */
  value?: (row: any) => string;
  /**
   * Extra per-row buttons, beside delete.
   *
   * A data point's event detectors are the case that needs it: they hang off the point rather than
   * off the data source, so they are reached from the point's row and nowhere else.
   */
  rowActions?: {icon: string; tooltip: string; run: (row: any) => void}[];
}

export interface GatewayModelDialogData {
  title: string;
  /** The model as the gateway serialised it, or a bare `{modelType}` when adding. */
  model: any;
  /** The model's own fields, mapped from the gateway's schema. */
  properties: FormProperty[];
  /**
   * How to lay those fields out, for a model type that has been worked through.
   *
   * Its absence is what keeps this rollout one protocol at a time: a type with no layout renders
   * exactly as it did before, through `tb-dynamic-form`. See {@link GATEWAY_FORM_LAYOUTS}.
   */
  layout?: GatewayFormLayout;
  /** A data point's nested locator, whose concrete type follows its data source's protocol. */
  locatorProperties?: FormProperty[];
  locatorLayout?: GatewayFormLayout;
  /**
   * The locator's model type, which is how a protocol that needs a **component** rather than a
   * layout gets one.
   *
   * A layout is a constant, and some types need a list looked up instead -- `VIRTUAL.PL` offers
   * every numeric point on the gateway as an attraction target. Those types get a component
   * extending `GatewayFormComponent`, chosen by this value in the template. A `@switch` rather
   * than a registry on purpose: there is one entry, and a component created through
   * `ngComponentOutlet` would need its value binding wired by hand. Worth revisiting if the list
   * ever outgrows a screenful.
   */
  locatorType?: string;
  /**
   * The gateway a per-type component queries — a locator's, or the model's own.
   *
   * Unused by the generic renderer. Set for every data source dialog rather than only the types
   * that need it: which types those are is decided by the template's `@switch`, and a component
   * that arrives without it falls back to plain text boxes silently.
   */
  deviceId?: string;
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
  /** Why this dialog is read-only, when the reason is the model rather than the user's authority. */
  readonlyNote?: string;
  /** Rows belonging to this model, edited inline. See {@link GatewayModelChildren}. */
  children?: GatewayModelChildren;
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
  styleUrls: ['./gateway-model-dialog.component.scss'],
  standalone: false
})
export class GatewayModelDialogComponent
  extends DialogComponent<GatewayModelDialogComponent, any> {

  readonly isAdd: boolean;

  /** The value column is offered only for rows that have a value to read. */
  get childColumns(): string[] {
    return this.data.children?.readValue
      ? ['name', 'detail', 'value', 'enabled', 'actions']
      : ['name', 'detail', 'enabled', 'actions'];
  }

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
   * The form's values, minus the ones that would overwrite a default with nothing.
   *
   * **On an add, an empty field is dropped rather than sent as null.** The form builds a control
   * for every property the schema declares, so an untouched number posts `null` -- and the
   * gateway's models carry their defaults as Java field initialisers, which Jackson applies only
   * when the key is *absent*. A null lands on a primitive `int` as 0, and a Modbus/IP source is
   * then refused with "Must be greater than zero" on four fields the operator never saw. Leaving
   * them out is what lets `timeout = 500`, `maxReadBitCount = 2000` and the rest take effect.
   *
   * On an edit every key is sent, empty included: a field cleared on purpose is a real change and
   * the model being spread underneath would otherwise put the old value straight back.
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
      const empty = value === null || value === undefined || value === '';
      if (empty && (this.isAdd || secrets.has(id))) {
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
