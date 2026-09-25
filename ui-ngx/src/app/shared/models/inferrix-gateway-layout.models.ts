// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormPropertyType, FormSelectItem } from '@shared/models/dynamic-form.models';

/**
 * What a gateway's schema cannot say, written down per model type.
 *
 * The schema published by `/rest/v2/model-schemas` carries types, ranges, nesting, `readOnly` and
 * `writeOnly` — and that is the whole security boundary, so nothing here weakens it: a layout only
 * ever hides a field, narrows a free string to a fixed option list, or moves a field down the page.
 *
 * What it cannot carry is everything swagger has no word for. `VIRTUAL.PL.changeType` is declared
 * `{"type": "string"}`, so without this an operator has to type `ALTERNATE_BOOLEAN` by hand; which
 * of its ten values are legal depends on the point's `dataType`; and each one brings a different
 * set of fields, so the form otherwise shows all thirteen at once.
 *
 * The values are taken from the gateway's own Java, not from its webapp — see
 * {@link VIRTUAL_CHANGE_TYPES}.
 */
export interface GatewayFormLayout {
  /** Property ids never rendered, because they are edited elsewhere or have no effect here. */
  hidden?: string[];
  /** Property ids moved into the Advanced panel: real settings the gateway's own UI omits. */
  advanced?: string[];
  /**
   * Property ids shown but not editable, for a field the gateway's own provisioning owns.
   *
   * Not a security control and not a substitute for one: the gateway decides what it accepts, and
   * a field it will not take back is already `readOnly` in the schema. This is for the fields it
   * *would* take and should not be asked to — a mesh node's radio address, the attribute a point
   * reads — which describe hardware the platform cannot change by writing a number at it. Shown
   * rather than hidden because they are the first thing an operator opens a provisioned row to
   * check.
   */
  readonly?: string[];
  /**
   * The gateway creates this source's points, so Cortex does not offer to add one.
   *
   * Only the source's layout carries it. Its points still open, still toggle and still delete —
   * what goes is the button whose form would have nothing to fill in, because every locator field
   * of a provisioned point is {@link readonly}.
   */
  provisionedPoints?: boolean;
  /** A fixed option list for a property the schema declares as a bare string. */
  options?: {[id: string]: FormSelectItem[]};
  /** An option list chosen by another control's value. */
  gatedOptions?: {[id: string]: GatewayGatedOptions};
  /** A property rendered only while another control holds one of these values. */
  visibleWhen?: {[id: string]: {by: string; values: string[]}};
  /**
   * Explicit rows, by property id. Anything not named here follows the default: scalars pair up
   * two to a row in schema order, and everything else takes a row of its own.
   */
  rows?: string[][];
  /**
   * What a **new** model starts with, where the gateway's own VO has a default the schema does not
   * publish. Applied only where the key is absent, which is what an add looks like: a model read
   * back from the gateway carries every key, `null` included, so an explicit null is never
   * overwritten.
   */
  defaults?: {[id: string]: any};
}

export interface GatewayGatedOptions {
  /** The control whose value selects the option list. */
  by: string;
  /** Option lists, by the gate control's value. */
  table: {[gateValue: string]: FormSelectItem[]};
  /**
   * What the field becomes while the gate holds a value the table does not list. Left unset it
   * stays a select with nothing in it, which is right for a field that only ever holds an enum
   * constant. `startValue` is the exception: it is genuinely free text for every data type but
   * one, so it names `text` here.
   */
  unlisted?: FormPropertyType;
}

/**
 * Which `changeType` values each `dataType` admits.
 *
 * From `ChangeTypeVO.getChangeTypes(int dataTypeId)` in the gateway's own `virtual-ds` module,
 * which is what the device enforces. The gateway's webapp carries the same four lists, so this is
 * two independent sources agreeing — worth having, because its template then switches on
 * `'MULTISTATE'`, a case its own dropdown can never produce (the dropdown emits
 * `INCREMENT_MULTISTATE`). Picking "Increment" on a multistate point shows an empty form in the
 * gateway's UI. Handed over as a stack open item; this form uses the value the device defines.
 */
const VIRTUAL_CHANGE_TYPES: {[dataType: string]: FormSelectItem[]} = {
  BINARY: [
    {value: 'ALTERNATE_BOOLEAN', label: 'Alternate'},
    {value: 'NO_CHANGE', label: 'No change'},
    {value: 'RANDOM_BOOLEAN', label: 'Random'}
  ],
  MULTISTATE: [
    {value: 'INCREMENT_MULTISTATE', label: 'Increment'},
    {value: 'NO_CHANGE', label: 'No change'},
    {value: 'RANDOM_MULTISTATE', label: 'Random'}
  ],
  NUMERIC: [
    {value: 'BROWNIAN', label: 'Brownian'},
    {value: 'INCREMENT_ANALOG', label: 'Increment'},
    {value: 'NO_CHANGE', label: 'No change'},
    {value: 'RANDOM_ANALOG', label: 'Random'},
    {value: 'ANALOG_ATTRACTOR', label: 'Attractor'},
    {value: 'DECREMENT_ANALOG', label: 'Decrement'}
  ],
  ALPHANUMERIC: [
    {value: 'NO_CHANGE', label: 'No change'}
  ]
};

/** Change types carrying a bounded range: `min` and `max` on the VO. */
const RANGED = ['BROWNIAN', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG', 'RANDOM_ANALOG'];

/** Change types that count and so can wrap: `roll` on the VO. */
const ROLLING = ['INCREMENT_MULTISTATE', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG'];

/**
 * Layouts by model type, and by component name for the shared models that have no family.
 *
 * Only the types worked through so far appear. A type with no entry renders exactly as before —
 * every field the schema declares, in schema order — so this table is additive and a gateway
 * module nobody has written a layout for is never worse off than it is today.
 */
export const GATEWAY_FORM_LAYOUTS: {[modelType: string]: GatewayFormLayout} = {

  /**
   * A virtual point's simulator settings.
   *
   * Thirteen fields in the schema, of which the gateway shows at most six at once: the ten change
   * types are ten different simulators sharing one flat model, so `min` means nothing to a boolean
   * alternator and `volatility` means nothing to anything but the attractor. Each field is
   * therefore tied to the change types whose VO actually declares it, taken from the `*ChangeVO`
   * classes rather than from the webapp — which omits `values` and `roll` for
   * `INCREMENT_MULTISTATE` entirely, through the case-name bug above.
   *
   * `relinquishable` is hidden rather than advanced: a virtual point is generated on the gateway,
   * so there is no device control to hand a value back to.
   */
  'VIRTUAL.PL': {
    hidden: ['relinquishable', 'configurationDescription'],
    options: {
      dataType: [
        {value: 'BINARY', label: 'Binary'},
        {value: 'MULTISTATE', label: 'Multistate'},
        {value: 'NUMERIC', label: 'Numeric'},
        {value: 'ALPHANUMERIC', label: 'Alphanumeric'}
      ]
    },
    gatedOptions: {
      changeType: {by: 'dataType', table: VIRTUAL_CHANGE_TYPES},
      // A binary point starts true or false and nothing else; every other data type parses its
      // start value from free text (`VirtualPointLocatorVO.getStartValue`).
      startValue: {
        by: 'dataType',
        table: {BINARY: [{value: 'true', label: 'True'}, {value: 'false', label: 'False'}]},
        unlisted: FormPropertyType.text
      }
    },
    visibleWhen: {
      values: {by: 'changeType', values: ['INCREMENT_MULTISTATE', 'RANDOM_MULTISTATE']},
      roll: {by: 'changeType', values: ROLLING},
      min: {by: 'changeType', values: RANGED},
      max: {by: 'changeType', values: RANGED},
      change: {by: 'changeType', values: ['INCREMENT_ANALOG', 'DECREMENT_ANALOG']},
      maxChange: {by: 'changeType', values: ['BROWNIAN', 'ANALOG_ATTRACTOR']},
      volatility: {by: 'changeType', values: ['ANALOG_ATTRACTOR']},
      attractionPointXid: {by: 'changeType', values: ['ANALOG_ATTRACTOR']}
    },
    // What `VirtualPointLocatorVO` starts a new locator with (`dataTypeId = DataTypes.BINARY`,
    // `changeTypeId = ALTERNATE_BOOLEAN`). Without them a new point opens with an empty data type,
    // and `changeType` is gated on it -- so its list is empty and there is nothing to pick.
    defaults: {dataType: 'BINARY', changeType: 'ALTERNATE_BOOLEAN'},
    rows: [['dataType', 'changeType'], ['min', 'max'], ['maxChange', 'volatility']]
  },

  /**
   * A virtual data source, which is nothing but how often the simulator ticks.
   *
   * No overrides: the schema leaves `timePeriod` and the two fields the mapper already collects
   * under Advanced, which is what the gateway's own form shows plus the two it does not. Present
   * so the type is marked as worked through -- a type with no entry keeps the old renderer.
   */
  'VIRTUAL.DS': {},

  /**
   * One node on the Wirepas mesh, as a data source.
   *
   * Nothing here is a setting. `controllerAddress` is the mesh address of the controller the node
   * answers to and `publisherId` is the provisioning publisher that created the row — both written
   * by the gateway when the node joined, and both the first thing an operator opens the row to
   * read. The gateway's own form disables them for the same reason, along with `editPermission`,
   * which Cortex drops entirely.
   */
  'VIRTUAL_MESH_NODE.DS': {
    readonly: ['controllerAddress', 'publisherId'],
    provisionedPoints: true
  },

  /**
   * One attribute of a mesh node, as a point.
   *
   * Every field describes what the radio sends: `attributeId` is the attribute number on the node,
   * `type` is its wire encoding (`AttributeDataType`) and `dataType` is how the gateway stores it.
   * A mesh node does not take a new value for any of them — it reports them — so all four are
   * shown and none is editable, which is what the gateway's own form does.
   *
   * `settable` is included although the gateway's form omits it, because it is the one field here
   * an operator acts on: it is what puts the set-value control on a point, and on this locator it
   * is the live copy (`DataPointDao` writes `getPointLocator().isSettable()` over the point's own).
   * Read-only with the rest — a DO is writable because it is a DO.
   *
   * `relinquishable` is hidden because `VirtualMeshNodePointLocatorModel.toVO` never reads it: a
   * value typed there is discarded in the mapper, before the gateway sees the point at all.
   */
  'VIRTUAL_MESH_NODE.PL': {
    hidden: ['relinquishable', 'configurationDescription'],
    readonly: ['dataType', 'settable', 'attributeId', 'type']
  },

  /**
   * The fields every data point carries, whatever protocol it reads.
   *
   * The gateway's own point form shows none of them — `DatapointPropertiesComponent` exists in its
   * webapp and is referenced by no template — so what the operator came for is the locator, and
   * these sit below it under Advanced.
   *
   * Two are hidden rather than moved. `enabled` is the toggle in the points table, which calls a
   * dedicated endpoint rather than a save. `settable` is **dead on this model**: `DataPointDao`
   * writes `vo.getPointLocator().isSettable()` into the column and every runtime check reads the
   * locator, so the two controls the schema asks for would disagree with only one taking effect.
   * `readPermission` and `setPermission` go with `editPermission` on a data source — gateway-local
   * permission strings, meaningless to an operator who reaches the gateway through the platform.
   */
  DataPointModel: {
    hidden: ['enabled', 'settable', 'readPermission', 'setPermission'],
    advanced: ['deviceName', 'purgeOverride', 'purgePeriod', 'textRenderer',
      'loggingPropertiesModel']
  }
};

/**
 * The layout for a model type, or undefined.
 *
 * Read through `hasOwnProperty` rather than indexed directly. `modelType` arrives from the device
 * -- it is the Jackson discriminator on a model the gateway sent, or the `pointLocatorType` it
 * published -- and a plain object literal answers `constructor` and `toString` from its prototype
 * with a function. Truthy, so a gateway naming one would otherwise switch the form over to a
 * layout that is not one.
 */
export const gatewayFormLayout = (modelType: string): GatewayFormLayout | undefined =>
  typeof modelType === 'string'
    && Object.prototype.hasOwnProperty.call(GATEWAY_FORM_LAYOUTS, modelType)
    ? GATEWAY_FORM_LAYOUTS[modelType] : undefined;
