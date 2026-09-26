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
  /**
   * A fixed option list for a property the schema declares as a bare string.
   *
   * Also used to *relabel* an enum the schema already declares, where humanising its constants
   * reads wrongly -- `TCP_KEEP_ALIVE` would become "Tcp keep alive". Naming the same values is
   * what makes that safe: a list that dropped one would hide a transport the gateway accepts.
   */
  options?: {[id: string]: FormSelectItem[]};
  /** An option list chosen by another control's value. */
  gatedOptions?: {[id: string]: GatewayGatedOptions};
  /**
   * A property rendered only while another control holds one of these values.
   *
   * The values are compared with `includes`, so they are the control's own values rather than
   * their labels: an enum constant for a select, and `true`/`false` for a toggle, which is why
   * this is not `string[]`.
   */
  visibleWhen?: {[id: string]: {by: string; values: (string | number | boolean)[]}};
  /**
   * Explicit rows, by property id. Anything not named here follows the default: scalars pair up
   * two to a row in schema order, and everything else takes a row of its own.
   */
  rows?: string[][];
  /**
   * Which point locator this data source's points take, for a type the gateway will not say.
   *
   * `/v2/data-source-types` publishes `pointLocatorType` per type and answers null for
   * `BACNET_MSTP.DS` (measured on stack 5.1.0 and again on 5.1.1). Without it a source with no
   * points yet has nothing to build a locator form from, so its *first* point cannot be added at
   * all — the platform falls back to reading a sibling point's locator, and there is no sibling.
   *
   * Only ever a fallback: the gateway's own answer wins where it gives one, so this cannot
   * contradict the device. Carried here rather than in a table of its own because it is the same
   * kind of per-type knowledge as the rest of this descriptor, and inherits the same discipline —
   * it exists only for a type that has been worked through.
   */
  pointLocatorType?: string;
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
 * Every Modbus data type the gateway decodes, in the order its own table declares them.
 *
 * From `ModbusPointLocatorVO.MODBUS_DATA_TYPE_CODES`, not from the gateway's webapp. The gateway
 * serves the same list at `/v2/modbus/attributes/data-type` with translated names, and the proxy
 * does not carry that route -- it is a compile-time `ExportCodes` table, not per-install rows, so
 * unlike BACnet's object types there is nothing to look up. Hardcoding it costs a constant;
 * allowlisting the route would cost a platform release.
 *
 * `modbusDataType` is declared a bare string, so without this an operator types `FOUR_BYTE_FLOAT`
 * by hand and a typo is a 422 from the gateway's own `validate()`, which rejects anything the table
 * does not name.
 */
const MODBUS_DATA_TYPES: FormSelectItem[] = [
  {value: 'BINARY', label: 'Binary'},
  {value: 'TWO_BYTE_INT_UNSIGNED', label: '2-byte integer, unsigned'},
  {value: 'TWO_BYTE_INT_SIGNED', label: '2-byte integer, signed'},
  {value: 'TWO_BYTE_INT_UNSIGNED_SWAPPED', label: '2-byte integer, unsigned, swapped'},
  {value: 'TWO_BYTE_INT_SIGNED_SWAPPED', label: '2-byte integer, signed, swapped'},
  {value: 'FOUR_BYTE_INT_UNSIGNED', label: '4-byte integer, unsigned'},
  {value: 'FOUR_BYTE_INT_SIGNED', label: '4-byte integer, signed'},
  {value: 'FOUR_BYTE_INT_UNSIGNED_SWAPPED', label: '4-byte integer, unsigned, swapped'},
  {value: 'FOUR_BYTE_INT_SIGNED_SWAPPED', label: '4-byte integer, signed, swapped'},
  {value: 'FOUR_BYTE_INT_UNSIGNED_SWAPPED_SWAPPED',
    label: '4-byte integer, unsigned, swapped words and bytes'},
  {value: 'FOUR_BYTE_INT_SIGNED_SWAPPED_SWAPPED',
    label: '4-byte integer, signed, swapped words and bytes'},
  {value: 'FOUR_BYTE_FLOAT', label: '4-byte floating point'},
  {value: 'FOUR_BYTE_FLOAT_SWAPPED', label: '4-byte floating point, swapped'},
  {value: 'FOUR_BYTE_MOD_10K', label: '4-byte mod 10k'},
  {value: 'FOUR_BYTE_MOD_10K_SWAPPED', label: '4-byte mod 10k, swapped'},
  {value: 'SIX_BYTE_MOD_10K', label: '6-byte mod 10k'},
  {value: 'SIX_BYTE_MOD_10K_SWAPPED', label: '6-byte mod 10k, swapped'},
  {value: 'EIGHT_BYTE_MOD_10K', label: '8-byte mod 10k'},
  {value: 'EIGHT_BYTE_MOD_10K_SWAPPED', label: '8-byte mod 10k, swapped'},
  {value: 'EIGHT_BYTE_INT_UNSIGNED', label: '8-byte integer, unsigned'},
  {value: 'EIGHT_BYTE_INT_SIGNED', label: '8-byte integer, signed'},
  {value: 'EIGHT_BYTE_INT_UNSIGNED_SWAPPED', label: '8-byte integer, unsigned, swapped'},
  {value: 'EIGHT_BYTE_INT_SIGNED_SWAPPED', label: '8-byte integer, signed, swapped'},
  {value: 'EIGHT_BYTE_FLOAT', label: '8-byte floating point'},
  {value: 'EIGHT_BYTE_FLOAT_SWAPPED', label: '8-byte floating point, swapped'},
  {value: 'TWO_BYTE_BCD', label: '2-byte BCD'},
  {value: 'ONE_BYTE_INT_UNSIGNED_LOWER', label: '1-byte integer, unsigned, lower'},
  {value: 'ONE_BYTE_INT_UNSIGNED_UPPER', label: '1-byte integer, unsigned, upper'},
  {value: 'FOUR_BYTE_BCD', label: '4-byte BCD'},
  {value: 'FOUR_BYTE_BCD_SWAPPED', label: '4-byte BCD, swapped'},
  {value: 'CHAR', label: 'Char'},
  {value: 'VARCHAR', label: 'Varchar'}
];

/** The one data type a coil or input-status point can hold. See {@link MODBUS_RANGE_TYPES}. */
const MODBUS_BINARY_ONLY = MODBUS_DATA_TYPES.filter(item => item.value === 'BINARY');

/** Data types decoded as a number, which are the only ones a scale and offset apply to. */
const MODBUS_NUMERIC_TYPES = MODBUS_DATA_TYPES
  .filter(item => !['BINARY', 'CHAR', 'VARCHAR'].includes(item.value))
  .map(item => item.value);

/**
 * Which data types each register range admits.
 *
 * A coil and an input status are single bits on the wire, and modbus4j says so with an exception
 * rather than a validation error: `NumericLocator.validate()` throws `IllegalDataTypeException`
 * ("Only binary values can be read from Coil and Input ranges") the moment the gateway builds the
 * locator. The gateway's own form greys the picker out for those two ranges but leaves whatever
 * was selected before, so switching a 4-byte float point to COIL_STATUS there saves a point that
 * throws on every poll. Gating the list instead clears it, which costs one click and cannot save
 * a locator the device will refuse to construct.
 */
const MODBUS_RANGE_TYPES: {[range: string]: FormSelectItem[]} = {
  COIL_STATUS: MODBUS_BINARY_ONLY,
  INPUT_STATUS: MODBUS_BINARY_ONLY,
  HOLDING_REGISTER: MODBUS_DATA_TYPES,
  INPUT_REGISTER: MODBUS_DATA_TYPES
};

/**
 * Bit index within a register, 0-15.
 *
 * `ModbusUtils.validateBit` throws outside that range and the schema is no help: springdoc types
 * the Java `byte` as `{"type": "string", "format": "byte"}` -- base64 -- where the wire format is a
 * plain number, so the mapper produces a free text box for a field with sixteen legal values.
 */
const MODBUS_BITS: FormSelectItem[] =
  Array.from({length: 16}, (_unused, bit) => ({value: bit, label: String(bit)}));

/**
 * Encodings for a CHAR or VARCHAR point.
 *
 * Every one is in `StandardCharsets`, so `Charset.forName` resolves it on any JVM. The gateway's
 * own form offers "ASCII" and "RTU" here, taken from the Modbus *serial* encoding picker by way of
 * its translation keys -- `Charset.forName("RTU")` throws, so that second option cannot work.
 */
const MODBUS_CHARSETS: FormSelectItem[] = [
  {value: 'ASCII', label: 'ASCII'},
  {value: 'UTF-8', label: 'UTF-8'},
  {value: 'ISO-8859-1', label: 'ISO-8859-1'},
  {value: 'UTF-16BE', label: 'UTF-16 big-endian'},
  {value: 'UTF-16LE', label: 'UTF-16 little-endian'}
];

/**
 * Baud rates offered for a Modbus serial line.
 *
 * The one list here taken from the gateway's webapp rather than from its Java: `baudRate` is a
 * plain `int` the stack never bounds, so there is no enum to read. Its `BAUD_RATES` constant is
 * the thirteen an RS-232/RS-485 adapter is actually jumpered for, and a rate outside them is a
 * rate no device on the bus speaks. A select rather than a number box because the schema's
 * `{"type": "integer"}` would otherwise accept 9601.
 */
const MODBUS_BAUD_RATES: FormSelectItem[] =
  [110, 300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600]
    .map(rate => ({value: rate, label: String(rate)}));

/**
 * Serial line settings, from the gateway's own `com.inferrix.serial` enums.
 *
 * All five are declared `{"type": "string"}` in the schema -- the REST model holds each as a
 * `String` and converts with `Enum.valueOf` -- so without these an operator types `DATA_BITS_8`
 * by hand, and `ModbusSerialDataSourceModel.toVO` answers a typo with an unhandled
 * `IllegalArgumentException` rather than a validation message.
 *
 * The gateway serves the same five lists at `/v2/modbus/attributes/serial/*` and the proxy does
 * not carry those routes, for the reason {@link MODBUS_DATA_TYPES} gives: they return
 * `Enum::name` over a compile-time enum, so there is nothing per-install to look up. What is
 * added here is the labels -- those routes return the constants raw, which is what the gateway's
 * own dropdowns show.
 */
const MODBUS_FLOW_CONTROLS: FormSelectItem[] = [
  {value: 'NONE', label: 'None'},
  {value: 'RTSCTS', label: 'RTS/CTS'},
  {value: 'XONXOFF', label: 'XON/XOFF'}
];

const MODBUS_DATA_BITS: FormSelectItem[] = [
  {value: 'DATA_BITS_5', label: '5'},
  {value: 'DATA_BITS_6', label: '6'},
  {value: 'DATA_BITS_7', label: '7'},
  {value: 'DATA_BITS_8', label: '8'}
];

/** Declared 1, 1.5, 2 -- the enum's own order, which is not its `value()` order (1, 3, 2). */
const MODBUS_STOP_BITS: FormSelectItem[] = [
  {value: 'STOP_BITS_1', label: '1'},
  {value: 'STOP_BITS_1_5', label: '1.5'},
  {value: 'STOP_BITS_2', label: '2'}
];

const MODBUS_PARITY: FormSelectItem[] = [
  {value: 'NONE', label: 'None'},
  {value: 'ODD', label: 'Odd'},
  {value: 'EVEN', label: 'Even'},
  {value: 'MARK', label: 'Mark'},
  {value: 'SPACE', label: 'Space'}
];

/** Relabelled, not narrowed: the schema declares this one as an enum. Both values are acronyms. */
const MODBUS_SERIAL_ENCODINGS: FormSelectItem[] = [
  {value: 'RTU', label: 'RTU'},
  {value: 'ASCII', label: 'ASCII'}
];

/**
 * BACnet write priority, 1 (highest) to 16 (lowest).
 *
 * `BACnetDataSourceDefinition.validate` rejects anything outside that range, and the REST model
 * declares a bare `int` with no initialiser -- so an untouched field is 0 and the save is refused
 * on a control the operator never saw. A select rather than a number box for the same reason the
 * Modbus bit index is one: the legal set is small and the schema publishes no bounds.
 *
 * 16 is the default because it is the priority a supervisory system is expected to write at: it
 * is the lowest, so anything else commanding the same object keeps precedence.
 */
const BACNET_WRITE_PRIORITIES: FormSelectItem[] =
  Array.from({length: 16}, (_unused, index) => ({
    value: index + 1,
    label: index === 0 ? '1 (highest)' : (index === 15 ? '16 (lowest)' : String(index + 1))
  }));

/**
 * A BACnet master's own fields, which are the same over IP and over MS/TP.
 *
 * Three editable fields, which is the whole type -- everything else on the model is shared with
 * every other data source. `localDeviceConfig` is declared a bare string and is a key into
 * `/v2/bacnet/local-devices`, so it cannot be a layout constant and both transports get a
 * component; `validate` looks the value up and refuses one that resolves to nothing, which means a
 * gateway with no local device configured cannot host a BACnet data source at all.
 *
 * `covSubscriptionTimeoutMinutes` is defaulted for the reason Modbus serial's line settings are:
 * `BACnetDataSourceModel` declares a bare `int`, so an absent key is 0, `validate` rejects
 * anything below 1, and the VO's own 60 never applies. The number here is that 60.
 */
const BACNET_DATA_SOURCE: GatewayFormLayout = {
  defaults: {covSubscriptionTimeoutMinutes: 60},
  rows: [['localDeviceConfig', 'covSubscriptionTimeoutMinutes']]
};

/**
 * A BACnet point: which object on which device, read as what. The same over both transports.
 *
 * Two of its fields are lookups rather than constants -- the object types the gateway decodes,
 * and the properties of whichever type is chosen -- so this layout carries what is declarative
 * and {@link BacnetPointFormComponent} adds the rest. The property list is what makes the form
 * worth having: each property reports the data types it can be read as, which is the list
 * `BACnetDataSourceDefinition.validate` checks `dataTypeId` against.
 *
 * `configurationDescription` is the gateway's own rendering of the fields above it, and
 * `relinquishable` is never read: `BACnetPointLocatorModel.toVO` sets ten fields and that is not
 * one of them, so a control for it would change nothing.
 *
 * The defaults are a working point, not a guess at one. `propertyIdentifierId` has to be sent --
 * `toVO` calls `PropertyIdentifier.forName(...)` on it before anything validates, so an absent
 * one is a null-pointer exception rather than a message -- and `multiplier` has to be sent
 * because the model declares a bare `double`, so an absent one is 0 and every reading is
 * multiplied by it. That last one fails silently: the point saves, polls, and reports 0 forever.
 */
const BACNET_POINT: GatewayFormLayout = {
  hidden: ['relinquishable', 'configurationDescription'],
  options: {writePriority: BACNET_WRITE_PRIORITIES},
  visibleWhen: {
    // Validated 1-16 whatever the point is, so it is still sent while hidden -- what the gate
    // removes is a field that decides nothing on a point nobody can write to.
    writePriority: {by: 'settable', values: [true]},
    // `encodableToValue` applies `raw * multiplier + additive` on the numeric branch alone.
    multiplier: {by: 'dataType', values: ['NUMERIC']},
    additive: {by: 'dataType', values: ['NUMERIC']}
  },
  defaults: {objectTypeId: 'ANALOG_INPUT', propertyIdentifierId: 'present-value',
    dataType: 'NUMERIC', multiplier: 1, writePriority: 16},
  rows: [['remoteDeviceInstanceNumber', 'objectInstanceNumber'],
    ['objectTypeId', 'propertyIdentifierId'], ['multiplier', 'additive']]
};

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
   * A Modbus/IP data source: how to reach the device, and how hard to push it.
   *
   * Only the connection and the poll are on the front of the form. The rest are per-device limits
   * an operator changes when a particular PLC misbehaves -- how many registers it will return in
   * one request, whether it needs multi-register writes for a single value, how long to linger on
   * the socket -- and the gateway's own form puts all twenty-one on one page, which is why finding
   * the host takes a scroll there.
   *
   * `host` and `port` pair explicitly so `transportType` keeps a row of its own: the three read as
   * one address and the schema does not order them that way.
   */
  'MODBUS_IP.DS': {
    advanced: ['multipleWritesOnly', 'contiguousBatches', 'maxReadBitCount',
      'maxReadRegisterCount', 'maxWriteRegisterCount', 'discardDataDelay', 'logIO',
      'ioLogFileSizeMBytes', 'maxHistoricalIOLogs', 'lingerTime', 'scaleFactor',
      'maxBackOffPeriod', 'maxConcurrentConnections'],
    // The schema declares the three transports as an enum, so the mapper already builds the list.
    // This only relabels it: humanising a Java constant is right for `COIL_STATUS` and wrong for
    // an acronym, which would otherwise read "Tcp keep alive".
    options: {
      transportType: [
        {value: 'TCP', label: 'TCP'},
        {value: 'TCP_KEEP_ALIVE', label: 'TCP, keep alive'},
        {value: 'UDP', label: 'UDP'}
      ]
    },
    rows: [['host', 'port']]
  },

  /**
   * The same Modbus master over a serial line, which is nine fields the IP one does not have and
   * four fewer of the socket ones it does.
   *
   * Its points are `MODBUS.PL` -- the same locator, gated the same way -- so this is the data
   * source alone. What is new is the line settings, and they behave differently from the rest of
   * this table: five of them are declared `{"type": "string"}` while the gateway converts each
   * with `Enum.valueOf`, so a free text box here is not merely unhelpful. An empty one is worse
   * than a wrong one: `ModbusSerialDataSourceModel.toVO` calls `FlowControl.fromName(null)`
   * before anything validates, which is a null-pointer exception on the gateway rather than the
   * "required" message its own `validate()` was written to give. Hence the defaults below: five
   * of them are what the VO's constructor starts on, so a source saved without touching the line
   * settings gets the line the gateway would have given it. `encoding` is the exception -- the
   * constructor leaves it null, which is the one value it cannot be sent as -- so RTU is this
   * layout's choice rather than the gateway's, taken because it is the framing a Modbus serial
   * device speaks unless configured otherwise.
   *
   * `commPortId` stays free text. The gateway does publish its ports, at
   * `/v2/utilities/gw/serial-ports`, but that route is not in the proxy allowlist and adding one
   * is a platform release -- so an operator types the port name their gateway reports. Recorded
   * as a gap rather than guessed at.
   */
  'MODBUS_SERIAL.DS': {
    // The same tuning as MODBUS_IP.DS, minus the four socket fields a serial line has no use for,
    // plus three of its own. Flow control is NONE on every RS-485 bus and `echo` is a property of
    // the adapter, not of the poll -- real settings, rarely the reason anyone opened this form.
    advanced: ['multipleWritesOnly', 'contiguousBatches', 'maxReadBitCount',
      'maxReadRegisterCount', 'maxWriteRegisterCount', 'discardDataDelay', 'logIO',
      'ioLogFileSizeMBytes', 'maxHistoricalIOLogs', 'flowControlIn', 'flowControlOut', 'echo'],
    options: {
      baudRate: MODBUS_BAUD_RATES,
      flowControlIn: MODBUS_FLOW_CONTROLS,
      flowControlOut: MODBUS_FLOW_CONTROLS,
      dataBits: MODBUS_DATA_BITS,
      stopBits: MODBUS_STOP_BITS,
      parity: MODBUS_PARITY,
      encoding: MODBUS_SERIAL_ENCODINGS
    },
    // `ModbusSerialDataSourceVO`'s constructor, field for field -- except `encoding`, which the
    // constructor leaves null and `validate()` then asks for. RTU is the framing every Modbus
    // serial device defaults to; ASCII is the opt-in.
    defaults: {baudRate: 9600, flowControlIn: 'NONE', flowControlOut: 'NONE',
      dataBits: 'DATA_BITS_8', stopBits: 'STOP_BITS_1', parity: 'NONE', encoding: 'RTU'},
    rows: [['commPortId', 'baudRate'], ['dataBits', 'stopBits'], ['parity', 'encoding'],
      ['flowControlIn', 'flowControlOut']]
  },

  /**
   * A Modbus point: which register, decoded how.
   *
   * Six of the nineteen fields never reach the device. `dataType` and `settable` are computed by
   * `ModbusPointLocatorVO` from `slaveMonitor`, `modbusDataType`, `multistateNumeric` and
   * `writeType`, so a control for either would contradict the fields that decide it; `rangeId` and
   * `modbusDataTypeId` are derived getters Jackson serialises and no setter reads; and
   * `ModbusPointLocatorModel.toVO` never touches `relinquishable`. Hidden rather than read-only:
   * there is nothing here for an operator to check, only a duplicate of what is above.
   *
   * The rest follow the register range, which is what decides the shape of a Modbus point. Taken
   * from the VO and from modbus4j's own locator classes -- the gateway's form agrees field for
   * field, which is two independent sources for every rule below, and disagrees only in greying a
   * field out where this hides it.
   */
  'MODBUS.PL': {
    hidden: ['dataType', 'settable', 'relinquishable', 'configurationDescription', 'rangeId',
      'modbusDataTypeId'],
    options: {bit: MODBUS_BITS, charset: MODBUS_CHARSETS},
    gatedOptions: {modbusDataType: {by: 'range', table: MODBUS_RANGE_TYPES}},
    visibleWhen: {
      // A bit index is read from a register; on a coil range modbus4j ignores it, and the gateway's
      // own form greys it out there. Gated on the data type rather than the range because that is
      // the field that decides it -- which does leave it showing on a coil point, where the range
      // has already forced the type to BINARY.
      bit: {by: 'modbusDataType', values: ['BINARY']},
      registerCount: {by: 'modbusDataType', values: ['CHAR', 'VARCHAR']},
      charset: {by: 'modbusDataType', values: ['CHAR', 'VARCHAR']},
      // `settableRange()`: a coil and a holding register are the two a master may write.
      writeType: {by: 'range', values: ['COIL_STATUS', 'HOLDING_REGISTER']},
      multiplier: {by: 'modbusDataType', values: MODBUS_NUMERIC_TYPES},
      additive: {by: 'modbusDataType', values: MODBUS_NUMERIC_TYPES},
      // The one field `getDataTypeId()` reads to choose between MULTISTATE and NUMERIC, and it
      // reaches that branch only for a type that is neither binary nor a string.
      multistateNumeric: {by: 'modbusDataType', values: MODBUS_NUMERIC_TYPES}
    },
    // What `ModbusPointLocatorVO` starts on (`range = 1`, `modbusDataType = 1`). The model's own
    // fields are null until set, and `validate()` rejects both as "invalid value" -- so without
    // these a new point cannot be saved until the operator has found the two fields that say so.
    defaults: {range: 'COIL_STATUS', modbusDataType: 'BINARY'},
    rows: [['slaveId', 'offset'], ['registerCount', 'charset'], ['multiplier', 'additive']]
  },

  /** A BACnet/IP master: which local device it speaks through, and how often. {@link BACNET_DATA_SOURCE} */
  'BACNET_IP.DS': BACNET_DATA_SOURCE,

  /**
   * The same master over an MS/TP serial bus.
   *
   * Field for field the same model as `BACNET_IP.DS` — the serial settings an operator expects
   * here (`commPortId`, `baudRate`, `thisStation`, `maxMaster`) are on the **local device**, not
   * on the data source, so choosing the right local device is the whole of choosing the bus. The
   * picker is filtered to MS/TP local devices for that reason: nothing on the gateway checks that
   * a source's transport matches the local device it names, and `LocalDeviceFactory` builds
   * whatever the config says — so an MS/TP source pointing at an IP local device silently speaks
   * BACnet/IP.
   *
   * `pointLocatorType` is the one addition. The gateway answers null for this type, which leaves
   * a source with no points unable to gain its first one.
   */
  'BACNET_MSTP.DS': {...BACNET_DATA_SOURCE, pointLocatorType: 'BACNET_MSTP.PL'},

  /** A BACnet/IP point: which object on which device, read as what. {@link BACNET_POINT} */
  'BACNET_IP.PL': BACNET_POINT,

  /**
   * An MS/TP point, which is a BACnet point: `BACnetMstpPointLocatorModel` adds nothing to
   * `BACnetPointLocatorModel`, and the schema agrees field for field. Shared rather than copied,
   * so the two cannot drift.
   */
  'BACNET_MSTP.PL': BACNET_POINT,

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
