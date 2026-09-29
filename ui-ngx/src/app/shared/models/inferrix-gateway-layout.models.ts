// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormProperty, FormPropertyType, FormSelectItem } from '@shared/models/dynamic-form.models';

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
  /**
   * How to render a field the schema types too loosely to lay out.
   *
   * `META.PL.script` is declared `{"type": "string"}` with no `format`, so the mapper gives it the
   * same single-line box as a host name — and what goes in it is a JavaScript function body. Swagger
   * has no word for "long text"; this supplies it.
   *
   * The type comes from this table and never from the device, which is what keeps it inside the same
   * boundary as the rest of the descriptor. It also only ever moves a field *between* the types the
   * form lays out itself: turning a delegated array into a text box would replace its editor with
   * one that cannot hold its value, so such an entry is ignored rather than obeyed.
   */
  types?: {[id: string]: FormPropertyType};
  /**
   * Fields the form refuses to submit empty, because the gateway cannot accept an empty one.
   *
   * Adds to whatever the schema already marks `required`, never subtracts — so this cannot weaken a
   * constraint the device declared, only state one it failed to. `MQTT.DS.brokerUri` is why it
   * exists: `validateURI` calls `new URI(vo.getBrokerUri())` and then `uri.getScheme().hashCode()`,
   * so an absent broker URI **and an empty one** are both a 500 rather than a message on the field
   * (measured 2026-09-27, filed as **D71**). A field that can only ever be answered has to be
   * answered here rather than at the gateway.
   *
   * Never name a field that is also {@link advanced}: the panel is collapsed, so the save would be
   * blocked by a control the operator cannot see. There is a spec for that.
   */
  required?: string[];

  /**
   * Fields whose empty value must still be sent on an add, because the gateway wants the key rather
   * than a value.
   *
   * The add drops every empty field so that a model's Java field initialisers apply -- which is what
   * makes a Modbus source's four timeouts work. This names the fields where that is wrong.
   *
   * Deleted on 2026-09-29 when D69 took its last user, `SNMP.DS.contextName`, and restored the same
   * day for a worse one: `SCRIPTING.DS.scriptPermissions` is not merely refused when absent, it is an
   * **HTTP 500**. `ScriptDataSourceModel.toVO` calls `new ScriptPermissions(scriptPermissions)` and
   * that constructor does `groups.split(",")` with no null check, so an add that drops the key
   * crashes the request (D101). Named per field rather than inferred, because there is no signal in
   * the schema for it -- `required` is not set, and the empty value is legal.
   */
  sendEmpty?: string[];

  /**
   * What a field means, where the schema does not say.
   *
   * The mapper already fills `FormProperty.hint` from the property's `description`, and the template
   * already renders it as the tooltip on an info icon in the field — so this is the same channel, for
   * the types whose schema carries no description at all. Three of the ten worked so far carry none
   * (`MQTT`, `HTTP_RECEIVER`, `HTTP_JSON_RETRIEVER`), and before this key existed the only place to
   * put a syntax rule or a unit was inside the label, which is not where ThingsBoard puts one.
   *
   * Given precedence over a description the device sent, because a layout naming a hint has looked at
   * that description and decided against it. Two limits: a **delegated** field has no hint channel —
   * `tb-dynamic-form` draws its own rows and the template's icon is not among them — so a hint on an
   * array or a fieldset is silently dropped, and the label is the only place left. And a `switch`
   * renders its hint on the toggle's own label rather than in a field.
   */
  hints?: {[id: string]: string};

  /**
   * The smallest value a number field may hold, where the gateway has a rule and the schema has no
   * `minimum`.
   *
   * `validatorsFor` already honours `FormProperty.min` on a number — this fills it in where the
   * document left it out. It exists because `required` is not the guard it looks like: Angular's
   * `Validators.required` treats `0` as a value, so a field whose gateway-side rule is `> 0` is still
   * submittable as zero. `HTTP_JSON_RETRIEVER.DS.timeoutSeconds` is the case, and there the refusal
   * that comes back names another field entirely (**D78**).
   *
   * Numbers only: `validatorsFor` applies it on {@link FormPropertyType.number} and nowhere else.
   */
  min?: {[id: string]: number};
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
 * The three QoS levels a gateway MQTT client will accept.
 *
 * `QosType` declares a fourth, `FAILURE(128)`, and leaves it out of `VALID_TYPES` -- it is what a
 * broker answers with, not something a client asks for, and `MqttDataSourceDefinition.validate`
 * refuses it on both the data source and the point ("Invalid choice", measured). The numbers are the
 * enum's own `byteValue`, which is the MQTT level, and the constant is spelled `ATLEAST_ONCE`
 * without the second underscore.
 */
const MQTT_QOS_TYPES: FormSelectItem[] = [
  {value: 'AT_MOST_ONCE', label: 'At most once (0)'},
  {value: 'ATLEAST_ONCE', label: 'At least once (1)'},
  {value: 'EXACTLY_ONCE', label: 'Exactly once (2)'}
];

/**
 * How a point's payload is written and read, for the four `DataSourceTopicType.VALID_TYPES`.
 *
 * `NONE` is declared and excluded, and the validator quotes the topic back when it is sent
 * ("Invalid publish topic type for topic …", measured). `INFERRIX_JSON` is the only one with a model
 * class behind it and is what the VO starts on.
 *
 * Relabelled because the gateway cannot label them itself: the enum registers `mqtt.topicType.*`
 * message keys and `i18n_en.properties` carries none of them, so its own dropdown is blank. Same for
 * the QoS list above (**D72**).
 */
const MQTT_TOPIC_TYPES: FormSelectItem[] = [
  {value: 'PLAIN', label: 'Plain value'},
  {value: 'JSON', label: 'JSON'},
  {value: 'JSON_WITH_TIMESTAMP', label: 'JSON with timestamp'},
  {value: 'INFERRIX_JSON', label: 'Inferrix JSON'}
];

/**
 * What triggers a meta point's script, in the gateway's own words.
 *
 * A relabel of an enum the schema already declares, and the one place its first option is legible.
 * `MetaPointLocatorVO` registers `UPDATE_EVENT_NONE = 0` under the message key
 * `dsEdit.meta.event.context`, and the six period constants are `TimePeriods` ids rather than
 * durations -- `MINUTES` is the *start of each minute*, not "every N minutes". The stack's own
 * property file has no entry for that first key, so the gateway's UI cannot show the label its code
 * asks for; these are the words the other seven carry.
 */
const META_UPDATE_EVENTS: FormSelectItem[] = [
  {value: 'NONE', label: 'Context update'},
  {value: 'MINUTES', label: 'Start of minute'},
  {value: 'HOURS', label: 'Start of hour'},
  {value: 'DAYS', label: 'Start of day'},
  {value: 'WEEKS', label: 'Start of week'},
  {value: 'MONTHS', label: 'Start of month'},
  {value: 'YEARS', label: 'Start of year'},
  {value: 'CRON', label: 'Cron pattern'}
];

/**
 * Which change on a flagged context point re-runs the script.
 *
 * Humanising these gives "Context update", "Context change" and "Context logged", which repeat the
 * field's own label and say nothing about the difference -- the choice is between *any* write, a
 * write that changes the value, and a write that is logged. Same three values under plainer names.
 */
const META_CONTEXT_UPDATE_EVENTS: FormSelectItem[] = [
  {value: 'CONTEXT_UPDATE', label: 'Any update'},
  {value: 'CONTEXT_CHANGE', label: 'Value change'},
  {value: 'CONTEXT_LOGGED', label: 'Logged value'}
];

/**
 * The three SNMP versions, which the schema declares as a bare string.
 *
 * `SnmpVersion` declares exactly these -- `v1(0)`, `v2c(1)`, `v3(3)` -- and
 * `SnmpDataSourceDefinition.validate` accepts those three ids and nothing else. Spelled the way
 * SNMP spells them, which is also the spelling `SnmpSettings.getSnmpVersionId` matches on.
 */
const SNMP_VERSIONS: FormSelectItem[] = [
  {value: 'v1', label: 'v1'},
  {value: 'v2c', label: 'v2c'},
  {value: 'v3', label: 'v3'}
];

/**
 * The SNMP types a settable point can be written as.
 *
 * `SnmpPointLocatorVO.SET_TYPE_CODES`, in its declared order, which is also its id order.
 * `NONE` is not a type but the absence of one: `isSettable()` is `setType != 0`, so this list is
 * how a point is made writable at all.
 */
const SNMP_SET_TYPES: FormSelectItem[] = [
  {value: 'NONE', label: 'Not settable'},
  {value: 'INTEGER_32', label: 'Integer (32-bit)'},
  {value: 'OCTET_STRING', label: 'Octet string'},
  {value: 'OID', label: 'OID'},
  {value: 'IP_ADDRESS', label: 'IP address'},
  {value: 'COUNTER_32', label: 'Counter (32-bit)'},
  {value: 'GAUGE_32', label: 'Gauge (32-bit)'},
  {value: 'TIME_TICKS', label: 'Time ticks'},
  {value: 'OPAQUE', label: 'Opaque'},
  {value: 'COUNTER_64', label: 'Counter (64-bit)'}
];

/**
 * The v3 authentication and privacy protocols, relabelled rather than narrowed.
 *
 * `AuthProtocols` and `PrivProtocols` are published as enums, so the mapper already offers exactly
 * these values -- what it cannot do is spell them. Its humaniser lower-cases all but the first
 * letter of a constant, which turns `MD5` into "Md5" and `AES256` into "Aes256": names an operator
 * has to match against their agent's own configuration, where they are written the way they are
 * written here.
 */
const SNMP_AUTH_PROTOCOLS: FormSelectItem[] = [
  {value: 'NONE', label: 'None'},
  {value: 'MD5', label: 'MD5'},
  {value: 'SHA', label: 'SHA'}
];

const SNMP_PRIV_PROTOCOLS: FormSelectItem[] = [
  {value: 'NONE', label: 'None'},
  {value: 'DES', label: 'DES'},
  {value: 'AES128', label: 'AES128'},
  {value: 'AES192', label: 'AES192'},
  {value: 'AES256', label: 'AES256'}
];

/**
 * Which SNMP version a field belongs to.
 *
 * `SnmpDataSourceDefinition.validate` branches on the version and asks for a different set either
 * side of it: v1 and v2c authenticate with a community string, v3 with a user, an authentication
 * protocol and a privacy protocol. A gate rather than two layouts, because it is one model and the
 * operator changes their mind about the version inside one form.
 */
const SNMP_COMMUNITY_VERSIONS: (string | number | boolean)[] = ['v1', 'v2c'];

/**
 * Layouts by model type, and by component name for the shared models that have no family.
 *
 * Only the types worked through so far appear. A type with no entry renders exactly as before —
 * every field the schema declares, in schema order — so this table is additive and a gateway
 * module nobody has written a layout for is never worse off than it is today.
 */
/**
 * The four data types a locator can carry when its runtime cannot make an image.
 *
 * `DataTypes` has five and the schema declares all five on every point locator, because the column
 * holds any of them -- but whether a given locator can *produce* one is the runtime's business, and
 * three of the types worked so far answer an image with a throw rather than a refusal:
 * `SnmpPointLocatorRT.variableToValue` and `HttpJsonRetrieverPointLocatorRT.parseValue` both end their
 * switch on `default: throw`, and `JavaScriptService.coerce` ends its chain of data-type branches the
 * same way. Nothing validates `dataType` on any of those three, so a point saved as IMAGE is accepted
 * and then fails on every poll for ever, reading nothing.
 *
 * Deliberately **not** applied to every locator: `HTTP_RECEIVER.PL` really does support an image, via
 * `PointValue.stringToValue`'s own `DataTypes.IMAGE` branch, which builds an `ImageValue` out of the
 * posted string. Which types belong here is read per type, from the code that converts the value.
 */
/**
 * The one attribute a mesh controller reports.
 *
 * `MeshControllerAttributes` declares exactly one constant -- `HEARTBEAT(1, "HEARTBEAT", BINARY, …)`
 * -- and the wire carries the **name**, which `MeshControllerPointLocatorModel.toVO` resolves through
 * a code table. Written out here rather than left as free text because the gateway's own check is
 * broken in a way that cannot be worked around from this side: see `MESH_CONTROLLER.PL` (**D90**).
 */
const MESH_CONTROLLER_ATTRIBUTES: FormSelectItem[] = [
  {value: 'HEARTBEAT', label: 'Heartbeat'}
];

/** The data type that one attribute reports, and the only one its conversion can produce. */
const BINARY_ONLY: FormSelectItem[] = [{value: 'BINARY', label: 'Binary'}];

/**
 * `PoeLightingPointLocatorVO.PointType`, written out because the schema publishes the field as a
 * bare `{"type": "string"}` -- no enum, no description -- while `toVO` does
 * `PointType.valueOf(pointType)` on it. The labels are the enum's own `value()` strings; the values
 * are the constant names, which is what the wire takes. Measured: the display name "Channel Level"
 * is a 400, an unknown name is a 400, and an omitted one is a **500**.
 */
/**
 * The three `ContextUpdateEvent` constants `ScriptingDataSourceDefinition.commonValidation` accepts.
 * The schema publishes all five; `NONE` and `CRON` are refused *"Invalid value"* on this type
 * (measured). Labels are the gateway's own words for them.
 */
const SCRIPTING_UPDATE_EVENTS: FormSelectItem[] = [
  {value: 'UPDATE', label: 'Update'},
  {value: 'CHANGE', label: 'Change'},
  {value: 'LOGGED', label: 'Logged'}
];

const POE_LIGHTING_POINT_TYPES: FormSelectItem[] = [
  {value: 'CHANNEL_LEVEL', label: 'Channel level'},
  {value: 'POWER_ON_SETTING', label: 'Power-on setting'}
];

const NON_IMAGE_DATA_TYPES: FormSelectItem[] = [
  {value: 'BINARY', label: 'Binary'},
  {value: 'MULTISTATE', label: 'Multistate'},
  {value: 'NUMERIC', label: 'Numeric'},
  {value: 'ALPHANUMERIC', label: 'Alphanumeric'}
];

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
    options: {dataType: NON_IMAGE_DATA_TYPES},
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
   *
   * `dataType` read-only is the one place the mapper's blanket `required` on a locator's data type
   * cannot bite: a disabled control is left out of Angular's validation entirely, so the rule is inert
   * here. It does not matter, because `provisionedPoints` on the data source removes the Add button --
   * there is no path through this form that could post an empty one -- and an edit carries the stored
   * type in from `fromVO`. If that data source ever gains an Add button, this needs a default.
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
   * A meta data source, which is nothing but a name and the events its scripts can raise.
   *
   * Every field the schema declares on it is one the platform already strips: the identity fields,
   * the two description fields, and the four the points table owns (`enabled`, the purge pair,
   * `editPermission`). What is left is `alarmLevels`. There is deliberately no poll period —
   * a meta point is driven by its own `updateEvent`, not by the source.
   *
   * Present so the type is marked as worked through, the way `VIRTUAL.DS` is.
   */
  'META.DS': {},

  /**
   * A meta point: a script, what triggers it, and the other points it can read.
   *
   * `script` is the form. It arrives as `{"type": "string"}` with no `format`, so without
   * {@link GatewayFormLayout.types} the operator writes a JavaScript body into a single-line box —
   * which is what this type was parked over, along with the point picker below.
   *
   * **`updateEvent` decides what else the form means, and `NONE` does not mean "never".** The
   * gateway registers it as `UPDATE_EVENT_NONE = 0`, the same id as `UPDATE_EVENT_CONTEXT_UPDATE`,
   * and labels it `dsEdit.meta.event.context` — "Context update". A point on `NONE` runs when a
   * context point it flagged changes, and `MetaPointLocatorVO.validate` then refuses it unless at
   * least one `context` entry carries `contextUpdate` (*"No points are set to update context"*,
   * measured 2026-09-27 on 5.1.x). Choosing a period or `CRON` lifts that: an empty context is
   * accepted. The labels here are the gateway's own words, which are clearer than the humanised
   * constants and are the only place the `NONE`/context equivalence is visible — the gateway's own
   * UI cannot show it, because the key it registers for that option has no translation.
   *
   * **Three fields are hidden because the gateway ignores them.**
   * `MetaPointLocatorVO.isSettable()` returns a hard `false`, so a meta point never takes a write;
   * `relinquishable` is not read by `toVO` at all; and `configurationDescription` is generated from
   * the script's first 40 characters. `scriptEngine` is hidden for the opposite reason — it is a
   * one-value enum, so there is nothing to choose, and it still has to be *sent*: the model maps it
   * through `ExportCodes.getId`, which answers `-1` for an absent value, and `-1` is not the
   * JavaScript engine. Since stack 5.1.3 the gateway defaults it itself (**D66**, measured: an omitted
   * `scriptEngine` reads back `JAVASCRIPT`), so the default here is belt-and-braces and the thing that
   * keeps this form working against an older gateway. `dataType` was the same quiet `-1`; that one is
   * now **refused** rather than stored (**D65**), which is why the mapper marks every locator's data
   * type required.
   *
   * **`scriptPermissions` is hidden, and that is a security decision rather than a tidiness one.**
   * It names the groups the script runs *as*, and `NashornScriptEngineDefinition.createEngine` reads
   * it to decide whether to hand out the confined engine or the one with Java access. The stack now
   * validates it against the caller's own groups (D58), so a Cortex operator cannot exceed
   * themselves — but an omitted value means no groups, which is the confined engine, and that is the
   * right thing for a form reachable from a browser to ask for. An existing point keeps whatever it
   * was given: the dialog spreads the stored model under the form's values, so a hidden field is
   * carried through a save untouched rather than blanked.
   *
   * The requirement `context` places on `updateEvent: NONE` is not expressed here. A layout can hide
   * a row and narrow a list; it has no word for "this array needs an entry when that select holds
   * this value", and the gateway's own refusal names the field and says why. The field's hint says
   * so too, in the schema's own words.
   */
  'META.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription', 'scriptEngine',
      'scriptPermissions'],
    advanced: ['executionDelaySeconds', 'logLevel', 'logSize', 'logCount'],
    types: {script: FormPropertyType.textarea},
    options: {updateEvent: META_UPDATE_EVENTS, contextUpdateEvent: META_CONTEXT_UPDATE_EVENTS,
      dataType: NON_IMAGE_DATA_TYPES},
    visibleWhen: {updateCronPattern: {by: 'updateEvent', values: ['CRON']}},
    defaults: {dataType: 'NUMERIC', scriptEngine: 'JAVASCRIPT', updateEvent: 'NONE',
      variableName: 'my', contextUpdateEvent: 'CONTEXT_UPDATE', executionDelaySeconds: 0,
      logLevel: 'NONE', logSize: 1, logCount: 5},
    rows: [['dataType', 'variableName'], ['updateEvent', 'updateCronPattern'],
      ['contextUpdateEvent', 'executionDelaySeconds'], ['logLevel', 'logSize']]
  },

  /**
   * An SNMP manager: which agent, over which version, with which credential.
   *
   * The version is the whole shape of this form. `SnmpDataSourceDefinition.validate` branches on
   * it and asks for a different set either side: v1 and v2c authenticate with a community string,
   * v3 with a security name, a context name and two protocols. Both sets are gated on it rather
   * than split into two layouts, because it is one model and an operator changes their mind about
   * the version while filling the form in — and a gated field keeps its control, so switching back
   * does not lose what was typed.
   *
   * Every v3 field is gated on the version and on nothing else, the two passphrases included. They
   * were first gated on their own protocol, which is the more precise condition — `NONE` means
   * there is nothing for a passphrase to be — and that was wrong for a reason worth keeping: a
   * gate hides a row but keeps its control, so a field gated on a *gated* field reads a value the
   * operator can no longer see. Choosing v3 and MD5 and then going back to v2c left
   * "Authentication passphrase" on a v2c form with the protocol that summoned it hidden.
   *
   * `authProtocol` and `privProtocol` are defaulted **for every version**, not only for v3, and
   * that is the load-bearing one. Both are `ReverseEnum`-backed on the model, and
   * `ReverseEnumMap.get` calls `Objects.requireNonNull` — so the constructor that builds the
   * response from the saved VO throws on a null. Measured on 5.1.1: omitting either answers 500
   * on a v2c source where neither protocol means anything, and the row is saved anyway and can no
   * longer be read (**D60**). `NONE` is what the operator would pick and what keeps the row
   * legible.
   *
   * The ports and the poll are the `int` family again — `SnmpDataSourceVO` starts on 161, 162,
   * 1000 and 2 while the model declares bare `int`s, so an absent key is 0 and `validate` refuses
   * it. `snmpVersion` has no VO default to take: the field is a bare `int` there too, so its 0 is
   * Java's rather than a decision, and v2c is the version a current agent speaks with the same
   * field set as v1.
   */
  'SNMP.DS': {
    advanced: ['retries', 'timeout', 'trapPort', 'maxRequestVars', 'localAddress',
      'engineId', 'contextEngineId'],
    options: {snmpVersion: SNMP_VERSIONS, authProtocol: SNMP_AUTH_PROTOCOLS,
      privProtocol: SNMP_PRIV_PROTOCOLS},
    visibleWhen: {
      readCommunity: {by: 'snmpVersion', values: SNMP_COMMUNITY_VERSIONS},
      writeCommunity: {by: 'snmpVersion', values: SNMP_COMMUNITY_VERSIONS},
      securityName: {by: 'snmpVersion', values: ['v3']},
      contextName: {by: 'snmpVersion', values: ['v3']},
      engineId: {by: 'snmpVersion', values: ['v3']},
      contextEngineId: {by: 'snmpVersion', values: ['v3']},
      authProtocol: {by: 'snmpVersion', values: ['v3']},
      privProtocol: {by: 'snmpVersion', values: ['v3']},
      // On the version, not on the protocol beside them, although the protocol is the more precise
      // condition. A gate keeps its control's value when it closes, so gating one gated field on
      // another lets a stale value through: pick v3 and MD5, go back to v2c, and the protocol
      // disappears while the passphrase it selected stays on screen. A rule reads one control, so
      // the fix is to read the one every field here already reads. What it costs is an inert
      // passphrase box on a v3 source with no authentication, which is the rarer wrong thing.
      authPassphrase: {by: 'snmpVersion', values: ['v3']},
      privPassphrase: {by: 'snmpVersion', values: ['v3']}
    },
    hints: {
      authPassphrase: 'Required once an authentication protocol other than None is chosen. The gateway '
        + 'never sends a passphrase back, so an empty box means unchanged rather than cleared — it '
        + 'restores whatever it already holds, and there is no way to blank a stored one. To stop '
        + 'using it, set the protocol back to None.',
      privPassphrase: 'Required once a privacy protocol other than None is chosen. Empty means '
        + 'unchanged, as above; set the protocol to None to stop using it.'
    },
    defaults: {snmpVersion: 'v2c', port: 161, trapPort: 162, timeout: 1000, retries: 2,
      authProtocol: 'NONE', privProtocol: 'NONE'},
    rows: [['host', 'port'], ['readCommunity', 'writeCommunity'],
      ['securityName', 'contextName'], ['authProtocol', 'authPassphrase'],
      ['privProtocol', 'privPassphrase'], ['engineId', 'contextEngineId']]
  },

  /**
   * An SNMP point: which OID, read as what, and whether it can be written.
   *
   * `settable` is hidden because it is an answer rather than a question.
   * `SnmpPointLocatorVO.isSettable()` returns `setType != 0` and
   * `SnmpPointLocatorModel.toVO` never sets it — it builds a fresh VO and copies seven fields,
   * none of them that one — so a control for it would change nothing while appearing to.
   * `Not settable` on the set type is the same statement, and it is the one the gateway reads.
   *
   * `configurationDescription` is the gateway's own rendering of the OID, and `relinquishable`
   * is not a field of the SNMP VO at all.
   *
   * Both silent-zero defaults are measured, not inferred. `multiplicand` is `1.0D` on the VO and a
   * bare `double` on the model, so an absent one stores 0 and scales every reading to nothing —
   * the same defect as BACnet's `multiplier`. `setType` is worse: `ExportCodes.getId(null)`
   * returns **-1**, which is not zero, so a point saved without one reports itself *settable* with
   * a set type no SNMP type answers to. Both were 201 Created with no warning (**D63**).
   */
  'SNMP.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    options: {setType: SNMP_SET_TYPES, dataType: NON_IMAGE_DATA_TYPES},
    visibleWhen: {
      // `SnmpPointLocatorRT` reads `binary0Value` on the binary branch alone: it is the raw value
      // that means 0, and there is nothing for it to mean on a numeric or multistate point.
      binary0Value: {by: 'dataType', values: ['BINARY']},
      multiplicand: {by: 'dataType', values: ['NUMERIC']},
      augend: {by: 'dataType', values: ['NUMERIC']}
    },
    defaults: {dataType: 'NUMERIC', setType: 'NONE', multiplicand: 1, binary0Value: '0'},
    rows: [['oid', 'dataType'], ['multiplicand', 'augend']]
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
  },

  /**
   * An MQTT client: which broker, which credential, and which topics it subscribes to.
   *
   * **The only data source so far with no polling period at all.** `MqttDataSourceVO` is not a
   * polling source -- it holds a live client and is driven by what the broker sends -- so there is no
   * `timePeriod` on the model and nothing here to put under a "Polling interval" label.
   *
   * `brokerUri` and `topicFilters` are {@link required} because the gateway cannot answer for either.
   * `validateURI` does `new URI(vo.getBrokerUri())` and then `uri.getScheme().hashCode()`, so an
   * absent *or* empty URI is a 500 with no field on it; `topicFilters` is a clean 422
   * ("validate.cannotContainEmptyString") and is required here so the operator is told before the
   * round trip rather than after it. Neither may move into Advanced: a required field behind a
   * collapsed panel blocks a save invisibly.
   *
   * `topicFilters` is a **newline-separated list**, split on `\n` and each line run through
   * `MqttTopic.validate(topic, true)` -- wildcards allowed, so `plant/+/temp` and `plant/#` are both
   * legal. One line in a single-line box is what the schema's bare string would otherwise give, hence
   * the textarea.
   *
   * `qosType` is the QoS applied to **every** one of those subscriptions:
   * `Arrays.fill(qosTypes, connectionParameters.getQosType().byteValue)` before `subscribe`. It is
   * defaulted because `toVO` resolves it with `QosType.valueOf`, which answers a missing value with a
   * 500 -- the same reason the three enums on the locator are defaulted.
   *
   * **The TLS fields, and why only two of them are gated.** `useCertificate` is the client-certificate
   * switch: with it off and a CA present the client does server-authenticated TLS
   * (`SslUtil.getSocketFactory`), and with it on and all three present it does mutual TLS
   * (`getAwsSocketFactory` -- the method is named for AWS IoT, which is what the flag is called
   * internally, but the mechanism is an ordinary client certificate). So the client certificate and
   * its key are gated on the switch and **the CA is not**: `validateURI` requires a CA for any
   * `ssl://` broker whatever the switch says, and gating it would hide the field that refusal names.
   *
   * `privateKey` is `writeOnly` and so arrives as a password field, which is what makes an empty one
   * mean "unchanged" on a save. It is deliberately *not* retyped to a textarea for that reason, even
   * though what goes in it is a PEM block: {@link GatewayModelDialogComponent.keep} keys on the
   * password type, and a textarea would turn a blank field into an erased key.
   *
   * The keep-alive and the timeout are the bare-`int` family again -- absent lands as 0, which
   * `validate` accepts (it only refuses negatives) and which means "no keep-alive" and "wait forever"
   * to Paho. 60 and 30 are the values Cortex's own broker dialog already seeds for the same fields on
   * the platform-integration client, so the two forms agree.
   */
  'MQTT.DS': {
    advanced: ['clientId', 'autoReconnect', 'cleanSession', 'keepAliveInterval',
      'connectionTimeout', 'useCertificate', 'x509CaCrt', 'x509ClientCrt', 'privateKey'],
    types: {topicFilters: FormPropertyType.textarea, x509CaCrt: FormPropertyType.textarea,
      x509ClientCrt: FormPropertyType.textarea},
    options: {qosType: MQTT_QOS_TYPES},
    required: ['brokerUri', 'topicFilters'],
    visibleWhen: {
      x509ClientCrt: {by: 'useCertificate', values: [true]},
      privateKey: {by: 'useCertificate', values: [true]}
    },
    defaults: {qosType: 'ATLEAST_ONCE', keepAliveInterval: 60, connectionTimeout: 30,
      autoReconnect: true, cleanSession: true, useCertificate: false},
    rows: [['brokerUri', 'qosType'], ['userName', 'userPassword'],
      ['keepAliveInterval', 'connectionTimeout']]
  },

  /**
   * An MQTT point: the topic it publishes to, the topic it subscribes to, and how each is encoded.
   *
   * **Both topics are required, and that is the model rather than an oversight.** The point validator
   * runs `MqttTopic.validate(topic, false)` over each -- no wildcards, length 1-65535 -- and a null
   * one arrives as a 422 quoting a Paho NPE ("Cannot invoke \"String.getBytes(String)\" because
   * \"topicString\" is null"). So there is no publish-only or subscribe-only MQTT point, which is
   * also why `settable` is hidden: `MqttPointLocatorVO.isSettable()` answers
   * `publishTopic != null && length > 0`, which a valid point always satisfies. It is derived, not
   * chosen. `relinquishable` is not read by `toVO` at all.
   *
   * **All three enums are defaulted because an absent one is a 500.** `toVO` resolves each with
   * `DataSourceTopicType.valueOf` / `QosType.valueOf` and no null check, and `toVO` runs before
   * `validate` -- so omitting `publishTopicType`, `subscribeTopicType` or `publishQosType` answers
   * `Internal Server Error` rather than a message on the field (measured; **D71**). The two topic
   * types start on `INFERRIX_JSON`, which is the VO's own default; the QoS has no VO default at all,
   * and at-least-once is what the rest of the product picks.
   *
   * There is no subscribe QoS. The data source's `qosType` is the one applied to every subscription
   * it makes, and this is the QoS the point publishes with.
   */
  'MQTT.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    options: {publishTopicType: MQTT_TOPIC_TYPES, subscribeTopicType: MQTT_TOPIC_TYPES,
      publishQosType: MQTT_QOS_TYPES},
    required: ['publishTopic', 'subscribeTopic'],
    defaults: {dataType: 'NUMERIC', publishTopicType: 'INFERRIX_JSON',
      subscribeTopicType: 'INFERRIX_JSON', publishQosType: 'ATLEAST_ONCE'},
    rows: [['publishTopic', 'publishTopicType'], ['publishQosType'],
      ['subscribeTopic', 'subscribeTopicType']]
  },

  /**
   * An HTTP receiver: what the gateway lets push to it.
   *
   * The whole type is an access list. There is no connection to configure and no poll -- the gateway
   * runs a servlet and devices POST to it -- so after the platform strips the identity, the
   * descriptions and the four the points table owns, the form is the two lists and nothing else.
   *
   * **Both are seeded because an absent one is a 500 rather than a message.**
   * `HttpReceiverDataSourceDefinition.validate` does `for (String ipmask : vo.getIpWhiteList())` over
   * each, and `toVO` copies the model's field straight across -- so a body that omits either throws
   * inside the validator (measured; **D74**). `HttpReceiverDataSourceVO` starts on `*.*.*.*` and `*`,
   * which is what is seeded here.
   *
   * **An empty list is accepted and rejects everything**, which is why both labels say so.
   * `InetAddressUtilities.ipWhiteListCheck` walks the array and returns false having found no match,
   * and `globWhiteListMatchIgnoreCase` answers false for a zero-length array before looking at
   * anything -- so an operator who deletes every row gets a receiver that silently drops every
   * request, and the gateway saves it 201. The lists are delegated arrays, so {@link required} cannot
   * reach them: a validator needs a control, and an array has none.
   *
   * The masks are dotted quads with `*` wildcards and **not CIDR**: `10.0.0.*` and `10.0.0.7` are
   * accepted, `10.0.0.0/8` is refused with *"Integer parsing error in '0/8'"*.
   */
  'HTTP_RECEIVER.DS': {
    // No hint on either list, although both now have a rule worth stating: an empty one is refused
    // (stack 5.1.3) rather than accepted and then dropping every request. An array is delegated to
    // `tb-dynamic-form`, which renders an array container with a title and no hint channel at all --
    // see the spec that forbids the attempt. The seeded rows are what keeps an operator away from
    // the rule; the gateway's refusal names the field if they get there anyway.
    defaults: {ipWhiteList: ['*.*.*.*'], deviceIdWhiteList: ['*']}
  },

  /**
   * An HTTP receiver point: which parameter of the pushed body it reads.
   *
   * `parameterName` is the whole locator -- `HttpReceiverDataSourceRT` matches it against the keys of
   * whatever was posted -- and `validate` refuses an empty one, so it is {@link required} rather than
   * left to the round trip.
   *
   * `dataType` is seeded for convenience rather than to avoid a crash: this is the one locator so far
   * whose validator checks it (`DataTypes.CODES.isValidId`), so an absent one is a clean 422 naming
   * the field rather than the silent `-1` of **D65**.
   *
   * `binary0Value` is gated on BINARY because that is the only branch that reads it:
   * `HttpReceiverDataSourceRT` compares the posted string against it only when
   * `dataTypeId == DataTypes.BINARY` **and** it is non-empty, and otherwise parses the string by data
   * type. Same field and same rule as `SNMP.PL`.
   *
   * `settable` is hidden because `isSettable()` returns a hard `false` -- a receiver is pushed to,
   * never written to -- and a submitted `true` is accepted and ignored. `relinquishable` is not read
   * by `toVO` at all.
   */
  'HTTP_RECEIVER.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    required: ['parameterName'],
    visibleWhen: {binary0Value: {by: 'dataType', values: ['BINARY']}},
    defaults: {dataType: 'NUMERIC', binary0Value: '0'},
    rows: [['parameterName', 'dataType']]
  },

  /**
   * A polling source that GETs one JSON document and reads each point out of it by JSON Pointer.
   *
   * `url` is {@link required} because the gateway requires it -- `validate` answers a blank one
   * `validate.required` under the field's own name, and a malformed one `validate.invalidValue`, so
   * this only moves the same refusal to where the operator is typing.
   *
   * **`timeoutSeconds` is required and floored because its refusal cannot be shown.**
   * `HttpJsonRetrieverDataSourceDefinition.validate` files its `timeoutSeconds <= 0` message under the
   * property name **`updatePeriods`** -- a field this type's form does not have -- so a zero or empty
   * timeout comes back as *"Must be greater than zero"* against nothing on screen (measured; **D78**).
   * Hence all three of seeding the model's own 30, {@link required} against an empty box, and
   * {@link min} `1`: `required` counts a typed `0` as an answer, and `0` is the value that produces the
   * unattributable refusal.
   *
   * **`retries` is a different case and D78 does not reach it.** `validate` refuses only `< 0` there and
   * files that under `retries`, its own name, so a wrong value says which field it is. It is required
   * for its own reason -- an empty box posts `null`, which Jackson lands on the primitive as 0, so the
   * source would silently stop retrying -- and floored at 0 rather than 1, which is what the gateway
   * allows.
   *
   * Both are ordinary fields rather than {@link advanced}: a {@link required} field behind the Advanced
   * toggle blocks a save from a control that is not on screen, which is the trap this is avoiding.
   *
   * `bearerToken` is a **textarea and deliberately not a password**, which is the opposite of every
   * other credential in this table. The schema does not mark it `writeOnly`, so the gateway hands the
   * token back in full on every read (**D79**) and the box is always populated on an edit -- there is
   * no case where the form does not know the stored value, so nothing to protect against. Typing it as
   * a password would buy masking that is cosmetic for a value the same response already carried in the
   * clear, and cost the one thing an operator needs from a credential field: `keep` drops an empty
   * password rather than sending it, so a cleared box would leave the stored token in place and a
   * token could never be removed. Empty means empty here, measured: `""` stores empty and `null`
   * stores null. The day the stack marks the field `writeOnly`, the mapper types it as a password
   * itself and `keep` starts protecting it -- which is then the right behaviour, because the read
   * would no longer carry the value. A textarea rather than a text box because tokens are long, which
   * is what the gateway's own form uses. It is gated on `bearerAuth` the way that form gates it.
   *
   * `setPointUrl` is {@link advanced} rather than hidden. It is inert today -- the locator's
   * `isSettable()` is a hard `false`, so no write ever reaches `setPointValue` (**D76**) -- but it is
   * stored, validated as a URL when non-blank, and would start working the moment that is fixed.
   */
  'HTTP_JSON_RETRIEVER.DS': {
    advanced: ['setPointUrl'],
    required: ['url', 'timeoutSeconds', 'retries'],
    // `required` alone would not stop the value D78 is about: Angular counts `0` as an answer, and the
    // gateway's rules are `timeoutSeconds > 0` and `retries >= 0`.
    min: {timeoutSeconds: 1, retries: 0},
    visibleWhen: {bearerToken: {by: 'bearerAuth', values: [true]}},
    defaults: {timeoutSeconds: 30, retries: 2, bearerAuth: false},
    // The switch and the field it gates on one row, which is the only way to put them in that order:
    // an explicit row lands where its first field falls, and the schema declares `bearerToken` before
    // the `bearerAuth` that reveals it -- so left alone the box appears above its own switch.
    //
    // The pairing rests entirely on the explicit row: `PAIRABLE_TYPES` is [text, password, number,
    // select] and a `switch` is not in it, so left alone `pack` would flush the toggle to a row of its
    // own and leave the box above it. With the gate closed the row collapses to the switch alone.
    rows: [['url'], ['timeoutSeconds', 'retries'], ['bearerAuth', 'bearerToken']]
  },

  /**
   * One value in that document: where to find it, and how to read what is there.
   *
   * `valuePointer` is {@link required} because the gateway requires it and because an empty one is
   * worse than a refusal: `pollPoints` collects only the points whose pointer is non-null, so a point
   * saved without one is never read and never says so. The label carries the leading slash, because it
   * is a **JSON Pointer** rather than a path -- `data/0/temp` is refused, `/data/0/temp` is not. The
   * gateway checks the syntax itself with `JsonPointer.valueOf`, so nothing here needs to; what it
   * cannot do is say so in English, because the message key is one of the 15 this type is missing
   * (**D80**).
   *
   * `valueFormat` is one field with two meanings, which is why its label names both. For NUMERIC it is
   * a `DecimalFormat` pattern applied to a textual value (and validated as one: a malformed pattern is
   * a clean 422). For BINARY it is the text that means 0 -- `new BinaryValue(!valueFormat.equals(…))`,
   * so anything else reads as 1. MULTISTATE and ALPHANUMERIC ignore it.
   *
   * `timeFormat` is only consulted when the timestamp node is textual: a `long` is taken as epoch
   * millis whatever is in here, and a textual node with no format is a parse event rather than a value.
   *
   * `dataType` is seeded because an absent one used to store `-1` and read back `null` -- the **D65**
   * silence, now a 422 instead, which is why the mapper requires it on every locator and a seed is what
   * keeps that refusal from being the operator's introduction to the rule.
   *
   * Only `relinquishable` is still hidden for the reason all three once were: `toVO` never looks at it.
   * `settable` and `ignoreIfMissing` were the same kind of dead field until stack 5.1.3 --
   * `isSettable()` was a hard `false`, and `ignoreIfMissing` was stored, serialised, mapped and read by
   * nothing, with the missing-value branch it documents raising the parse event either way. **D76** and
   * **D77** made both live, so both are shown.
   */
  'HTTP_JSON_RETRIEVER.PL': {
    hidden: ['relinquishable', 'configurationDescription'],
    // `valuePointer` is **not** required, although a point without one is never read: `pollPoints`
    // collects only the points whose pointer is non-null. Unhiding `settable` opened the case that
    // makes an unconditional rule wrong -- a write-only point, `settable` on with a set point key and
    // no pointer at all, which the gateway accepts (measured 201, `valuePointer` stored null). A
    // layout cannot say "required unless that switch is on", and inventing a refusal the gateway does
    // not make would leave a legitimate point unsaveable. The hint carries it instead.
    options: {dataType: NON_IMAGE_DATA_TYPES},
    visibleWhen: {setPointName: {by: 'settable', values: [true]}},
    hints: {
      settable: 'Lets the platform write this point back to the server it was read from. It needs a '
        + 'Set point JSON key here and a Set point URL on the data source; without both, the gateway '
        + 'refuses the point on its next save.',
      setPointName: 'The JSON key the written value is sent under: the gateway POSTs '
        + '{"<key>": <value>} to the data source\'s Set point URL.',
      valuePointer: 'Where the value is in the response, as a JSON Pointer: /data/0/temp. It has to '
        + 'start with a slash. A point with no pointer is never read — leave it empty only for a '
        + 'point that exists to be written.',
      valueFormat: 'Numeric points: a number pattern, such as #.## — used only when the value arrives '
        + 'as text. Binary points: the text that means 0, with anything else reading as 1. Ignored for '
        + 'the other types.',
      timePointer: 'Where that value\'s own timestamp is, as a JSON Pointer: /data/0/ts. Left empty, '
        + 'each value is stamped with the time of the poll.',
      timeFormat: 'Only read when the timestamp arrives as text: a date pattern, such as '
        + 'yyyy-MM-dd HH:mm:ss. A number is taken as milliseconds since the epoch.',
      ignoreIfMissing: 'When the value pointer finds nothing in a response, leave the point alone '
        + 'instead of raising a parse alarm. The poll still counts as parsed. For a response whose '
        + 'shape varies between polls.'
    },
    defaults: {dataType: 'NUMERIC'},
    // Data type first, because it decides what the two format fields mean. `valueFormat` takes a row
    // of its own so that the timestamp pair stays a pair: left to fall where it likes it would take
    // `timePointer` with it and leave `timeFormat` on its own.
    //
    // The set-point pair is named `setPointName` first **to put it last**, and that is the whole
    // reason for the order: `pack` seats an explicit row where its *first* member falls in schema
    // order, and `settable` is the second property the schema declares. Naming the switch first would
    // have landed the pair at the top, between the pointer and the formats -- which is what an earlier
    // draft of this comment claimed it did not. The cost is that the row reads key-then-switch rather
    // than switch-then-key; with the gate closed it is the switch alone, which is the common case, and
    // it is on the on-screen pass's list.
    rows: [['dataType', 'valuePointer'], ['valueFormat'], ['timePointer', 'timeFormat'],
      ['setPointName', 'settable']]
  },

  /**
   * The gateway watching itself: one poll period and one regex, and no connection to anything.
   *
   * `createPointsPattern` is the whole data source. `InternalDataSourceRT` compiles it once and, on
   * every poll, creates a point for each registered monitor whose **whole id** matches and that it is
   * not already tracking -- enabled, named from the monitor, with logging types chosen by what kind of
   * monitor it is. Left empty the source creates nothing, and its points are then added by hand, so
   * {@link provisionedPoints} would be wrong here: the button it removes is the only way to add a
   * point to a source with no pattern. The gateway's own `internal_monitoring_ds` is not the example:
   * `InternalLifecycleDefinition.postInitialize` installs its 23 points from a fixed table and re-runs
   * on every boot, so one deleted through Cortex comes back. That provisioner is for that single
   * source only -- nothing provisions a source an operator creates.
   *
   * The pattern matches ids and an operator can only see names (**D83**), so the hint carries the two
   * id shapes that cover most of what is worth tracking. A malformed regex is a clean 422 naming the
   * field, which is more than most of this gateway manages.
   */
  'INTERNAL.DS': {
    hints: {
      // Plain text: the template renders a hint as a Material tooltip, which does not read markdown, so
      // a backtick would be shown rather than applied.
      createPointsPattern: 'A Java regular expression, matched against each monitor\'s id rather than '
        + 'the name shown on the gateway\'s own Stack Monitor page. The whole id has to match, '
        + 'so wrap a fragment in .* -- COUNT on its own matches nothing and reports nothing. '
        + 'Every monitor it matches gets a '
        + 'point, created on the next poll and enabled. Examples: .*\\.COUNT for every row count, or '
        + 'com\\.inferrix\\.infix\\.rt\\.dataSource\\.PollingDataSource.* for the poll health of every '
        + 'data source on the gateway. Left empty, nothing is created.'
    }
  },

  /**
   * One monitored value, named by an id the gateway will not enumerate.
   *
   * Both fields are real since stack 5.1.3. `InternalPointLocatorModel.toVO` copies the data type now
   * (**D84**), so the dropdown is a dropdown -- it was {@link readonly} while the mapper discarded it
   * and kept the VO's `NUMERIC`, which also meant a `PUT` on the gateway's own ALPHANUMERIC
   * `internal_name_hardware` point reset it. Measured: `ALPHANUMERIC` now reads back `ALPHANUMERIC`.
   *
   * `IMAGE` is off the list even so, and this one is ours rather than the gateway's:
   * `InternalDataSourceRT.doPoll` writes a monitor's value through two branches, `Number` and
   * `String`, so an image point can be saved -- measured 201 -- and will never hold a value. The same
   * shape as the three locators **D82** closed, on a type D82 did not reach, filed as **D89**.
   *
   * `monitorId` is {@link required} because the gateway refuses both an absent id and one no monitor
   * answers to (**D85**, measured 422 *"No monitor with id …"*), and it is a picker rather than a text
   * box because `GET /v2/stack-monitor` publishes each monitor's id alongside its translated name
   * (**D83**). Both were the reverse a day ago: a wrong id saved 201 and read nothing for ever, and
   * the 98-row monitor list carried no id to pick from. {@link InternalPointFormComponent} fills the
   * list; the seeded default is what the form falls back to when the gateway cannot be reached.
   */
  'INTERNAL.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    required: ['monitorId'],
    options: {dataType: NON_IMAGE_DATA_TYPES},
    hints: {
      monitorId: 'Which monitored value this point reads. The list comes from the gateway; where it '
        + 'is empty the gateway could not be reached and this is the id itself, which is not the name '
        + 'the Stack Monitor page shows — they look like java.lang.Runtime.freeMemory or '
        + 'com.inferrix.stack.dao.DataPointDao.COUNT.',
      dataType: 'How the value is stored. Numeric for a count, a size or a load; Alphanumeric for a '
        + 'monitor that reports text, such as the hardware name.'
    },
    defaults: {monitorId: 'com.inferrix.stack.rt.maint.WorkItemMonitor.highPriorityWaiting',
      dataType: 'NUMERIC'},
    // Schema order puts `dataType` first, which would open the form on the secondary field. A row each
    // rather than a pair: an id is 60-odd characters and half a line cuts it off mid-package, and the
    // picker shows the name rather than the id only once the gateway has answered.
    rows: [['monitorId'], ['dataType']]
  },

  /**
   * A mesh controller node: three fields, and the mesh owns the first of them.
   *
   * The gateway creates this source itself. A controller joining the mesh reaches
   * `MeshControllerNodesDataSourceCreationManager`, and `CreateMeshControllerVO.createDataSource`
   * saves the source at the node's address and then calls `createDataPoints`, which creates one point
   * per `MeshControllerAttributes` constant -- one, `HEARTBEAT` -- with the attribute's own data type
   * and text renderer, and starts the source. That is {@link provisionedPoints}: the Add button would
   * only ever offer a second point for the one attribute that exists, which is a row the gateway never
   * makes and the runtime has no use for. The live source on the team's instance is exactly this shape
   * -- address 11, one BINARY `HEARTBEAT` point.
   *
   * `address` is {@link readonly}, which is the case {@link GatewayFormLayout.readonly}'s own
   * description gives as its example -- "a mesh node's radio address". An earlier draft left it
   * editable to keep the type addable by hand; three things say that was wrong. The gateway does not
   * mean this type to be created at all (`MeshControllerDataSourceDefinition.isEnabled()` returns
   * **false**, the flag whose interface javadoc is *"so that it could be listed in the dropdown
   * selection menu"*); a source created through REST gets **no points**, because `createDataPoints`
   * runs only from `CreateMeshControllerVO` and never from the REST path, and `provisionedPoints`
   * then removes the only button that could add one; and `deleteRelationalData` keys
   * `deviceCache.addRemoveDevices` and `MeshNodeInfoService.deleteByAddress` on `vo.getAddress()`, so
   * an edited address orphans the mesh-node row filed under the old one.
   *
   * It is **not** also {@link required} with a floor of 1, although that is the gateway's rule --
   * `validate` refuses `0` and `-1` and the model's own initialiser **is** `-1`, so an omitted address
   * is a 422 rather than a default (measured: 0, -1 and omitted all answer *"Invalid value"* against
   * `address`). Angular leaves a disabled control out of validation entirely, so the pair would read
   * as a rule and enforce nothing, which is what the `required`/`readonly` spec forbids. The rule
   * lives here instead, and it is the first thing to restore if this type ever becomes addable.
   *
   * `anchorNode` and `location` are both live -- `MeshControllerDataSourceRT` hands all three to
   * `MeshControllerMeshActionListener`, and `location` is the model's name for the VO's `zone`.
   */
  'MESH_CONTROLLER.DS': {
    provisionedPoints: true,
    readonly: ['address'],
    hints: {
      address: 'The node address the mesh assigned this controller. It identifies the node the '
        + 'gateway files this source and its mesh records under, so it is not editable here.',
      anchorNode: 'Anchor nodes are the fixed reference points the mesh measures position against. '
        + 'The gateway registers this one as an anchor when the source starts.',
      location: 'A free-text zone or place, stored as the node\'s zone and shown wherever the mesh '
        + 'console lists it.'
    },
    // The toggle falls on its own line, which is where a switch reads best; `location` comes up beside
    // the address rather than sitting under the toggle on a line of its own.
    rows: [['address', 'location']]
  },

  /**
   * One attribute of a mesh controller, as a point -- and there is one attribute.
   *
   * **The gateway cannot tell a wrong attribute from a right one, and this is why the list is ours.**
   * `MeshPointLocatorVO` declares a single `public static ExportCodes ATTRIBUTE_CODES`, and **34**
   * subclasses reassign that one field from their own static initialiser -- mesh extender, mesh
   * switch, every sensor tag, the thermostats, the light controllers. There is one table, and the
   * last class to initialise wins. `MeshControllerDataSourceDefinition.validate` checks
   * `MeshControllerPointLocatorVO.ATTRIBUTE_CODES.isValidId(...)`, which resolves to that shared
   * field. Measured on 5.1.3: a `MESH_CONTROLLER.PL` point saved **201** with
   * `attributeId: "BATTERY"`, which `MeshControllerAttributes` does not declare, and read back as
   * `"BATTERY"` -- so `fromVO`'s lookup is reading the wrong table too. The review narrowed the live
   * table further by probe: `HEARTBEAT` and `BATTERY` accepted, twenty other attribute names refused,
   * which is `MeshExtenderAttributes` exactly. Filed as **D90**. Writing the list out here is the only
   * thing on this side that refuses anything.
   *
   * `dataType` is `BINARY` alone for the same reason the attribute list has one entry:
   * `HEARTBEAT`'s conversion is `value -> new BinaryValue(value.getBooleanValue())`, and the
   * provisioner sets the attribute's own `DataTypes.BINARY`. Nothing on the gateway refuses another
   * type -- the definition only checks that the type exists -- so a numeric mesh controller point
   * would be a point the mesh writes a binary value to.
   *
   * `settable` and `relinquishable` are hidden because `MeshControllerPointLocatorModel.toVO` builds
   * a fresh VO and copies `attributeId` and `dataType` alone. `settable` is not read-only by
   * construction here -- `MeshPointLocatorVO.isSettable()` answers the stored field -- it is simply
   * never given a value. Measured: `settable: true` saves 201 and reads back `false`, and
   * `relinquishable` reads back `null`. The same shape D84 closed on `INTERNAL.PL`, filed as **D91**.
   */
  'MESH_CONTROLLER.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    // Both describe the radio rather than anything the platform chooses, which is what
    // {@link GatewayFormLayout.readonly} is for -- its description names "the attribute a point
    // reads" as the example -- and what {@link GatewayFormLayout.provisionedPoints} already claims of
    // every locator field on a provisioned point. The lists stay: a disabled select still needs its
    // item to render a label rather than the raw constant.
    readonly: ['attributeId', 'dataType'],
    options: {attributeId: MESH_CONTROLLER_ATTRIBUTES, dataType: BINARY_ONLY},
    defaults: {attributeId: 'HEARTBEAT', dataType: 'BINARY'},
    hints: {
      attributeId: 'What this point reads from the controller. The gateway creates this point when '
        + 'the controller joins the mesh, and a mesh controller reports one attribute — its '
        + 'heartbeat — so there is nothing to choose and nothing to change.'
    },
    rows: [['attributeId', 'dataType']]
  },

  /**
   * A ping data source, which is nothing but how often the gateway pings.
   *
   * No overrides, and field-identical to `VIRTUAL.DS`: `PingDataSourceVO` adds not one field to
   * `PollingDataSourceVO`, so the whole type is the poll period plus the two the mapper already
   * puts under Advanced. The gateway's own form agrees -- name, XID, polling interval and its unit,
   * edit permission, and nothing else. Present so the type is marked as worked through.
   *
   * `alarmLevels` is always `[]` here, because `getEventCodes()` returns null and `addEventTypes`
   * adds none -- measured on a created source. It is left where {@link GATEWAY_ADVANCED_FIELDS}
   * puts it rather than hidden, because `VIRTUAL.DS` is in exactly the same position and this is a
   * property of the two types' event tables, not of this row.
   */
  'PING.DS': {},

  /**
   * One address the gateway pings, as a point.
   *
   * The whole locator is `ipAddress` and `timeout`; everything else the schema lists is fixed by
   * construction. `PingPointLocatorVO.getDataTypeId()` returns `DataTypes.BINARY` with nothing
   * reading a submitted type -- `PingPointLocatorModel.toVO` copies the two fields and no more --
   * so the data type is read-only on one item with a default, which is what a point that is
   * reachable or not can be. Measured: a point submitted `dataType: NUMERIC` saves 201 and reads
   * back `BINARY`.
   *
   * Unlike `MESH_CONTROLLER.PL`, this data source has an Add button, so the default is what keeps
   * the mapper's blanket `required` on a locator's data type from refusing a new point: a disabled
   * control is left out of Angular's validation, and `gatewayFormDefaults` seeds the value the
   * control cannot supply.
   *
   * `settable` is hidden because `isSettable()` is false by construction -- `PingDataSourceRT`'s
   * `setPointValue` is an empty method -- and `relinquishable` with it, since `toVO` never reads
   * either. Measured: both submitted true, the point reads back `settable: false` and
   * `relinquishable: null`.
   *
   * Both remaining fields are `required`, which is the gateway's own rule rather than a stricter
   * one: `PingDataSourceDefinition.validate` refuses an empty address and a timeout of `<= 0`.
   * `min` carries the second half, because the refusal is a 422 the operator would otherwise only
   * meet on save -- and an omitted timeout is a zero, so it is refused too.
   */
  'PING.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    readonly: ['dataType'],
    options: {dataType: BINARY_ONLY},
    required: ['ipAddress'],
    min: {timeout: 1},
    hints: {
      ipAddress: 'What to ping. A host name works as well as an address — the gateway resolves it '
        + 'on every poll — and the point is on when the host answers, off when it does not.',
      timeout: 'How long to wait for an answer before calling the host unreachable. The gateway '
        + 'refuses zero, so a point always waits at least a millisecond.'
    },
    defaults: {dataType: 'BINARY', timeout: 1000},
    rows: [['ipAddress', 'timeout']]
  },

  /**
   * A PoE lighting controller, reached over its own HTTP API.
   *
   * `ipAddress` and `token` are the whole connection: `PoeLightingService.createHeaders` does
   * `headers.setBearerAuth(token)` and every call is `restTemplate.exchange(baseUrl + …)`. Neither
   * is validated anywhere -- `PoeLightingDataSourceDefinition.validate` is an empty method, and so
   * is its point overload -- so `required` here is **stricter than the gateway**, which this
   * feature otherwise avoids. It is justified on the same ground row 11 failed on: an empty value
   * is not a configuration the gateway can act on, it is a source that can never reach anything.
   *
   * `connectionTimeoutSeconds` is hidden because **nothing reads it**. It is declared on the VO
   * with a default of 10, serialised, mapped both ways by `PoeLightingDataSourceModel`, and never
   * referenced again anywhere in the tree -- unlike `retries`, which `PoeLightingDataSourceRT`
   * checks on every attempt. A knob that stores a value and changes no behaviour is worse than no
   * knob, and the field keeps its stored value because a data source saves with `PATCH`.
   *
   * `alarmLevels` really has rows on this type -- communication failure and device failure, both
   * URGENT -- which is the first time in the sequence it is worth the Advanced group it sits in.
   */
  'POE_LIGHTING.DS': {
    hidden: ['connectionTimeoutSeconds'],
    advanced: ['retries'],
    required: ['ipAddress', 'token'],
    min: {retries: 0},
    hints: {
      token: 'The API token the controller accepts, sent as a bearer token on every call. A '
        + 'controller found by discovery is given the one in the gateway\'s PoE lighting system '
        + 'settings, so a source added here is the place to use a different one.',
      retries: 'How many further attempts a failed set is given. Zero means the first failure is '
        + 'the last. The gateway\'s own discovery uses three.'
    },
    // What `new PoeLightingDataSourceVO()` holds. The REST model initialises the field to 0
    // instead, so a source created without it retries nothing -- measured, a POST omitting
    // `retries` stores 0. Discovery sets 3; the VO's own answer is 2, and that is the one a form
    // building a fresh source should start from.
    defaults: {retries: 2},
    rows: [['ipAddress', 'token']]
  },

  /**
   * One channel of a PoE lighting controller, as a point.
   *
   * `pointType` decides everything else: `getDataTypeId()` is a switch on it -- `CHANNEL_LEVEL` is
   * NUMERIC, `POWER_ON_SETTING` is BINARY -- and `getConfigurationDescription()` prints it beside
   * the channel. It arrives as a bare string with no enum, and `toVO` calls `valueOf` on it
   * unguarded, so the list is not a narrowing of the gateway's own choices but the only thing
   * standing between an operator and a 500. The gateway's own form gives up differently: it renders
   * `pointType` **read-only**, because nothing there adds a point by hand.
   *
   * `dataType` is hidden rather than disabled, which is a first. Every other locator in the
   * sequence either takes the submitted type or fixes it at one value; here it is derived from
   * another field on the same form, and the layout language has no way to say that. A disabled box
   * would show `NUMERIC` while the operator selects Power-on setting, which is worse than showing
   * nothing. Hidden is safe for the mapper's blanket `required`: `build` filters hidden properties
   * out before `addControl`, so there is no control to fail validation -- and the gateway fills the
   * field itself, measured, including when a submitted `ALPHANUMERIC` is discarded for `NUMERIC`.
   *
   * `settable` is live here -- `PoeLightingPointLocatorVO.isSettable()` answers the stored field and
   * `toVO` copies it -- so it is shown, and it defaults on, because every point the gateway's own
   * discovery creates is settable and a lighting channel that cannot be set is a light nobody can
   * switch. `relinquishable` is hidden: `toVO` never reads it, and it reads back null.
   */
  'POE_LIGHTING.PL': {
    hidden: ['dataType', 'relinquishable', 'configurationDescription'],
    options: {pointType: POE_LIGHTING_POINT_TYPES},
    required: ['channelId'],
    // `createDataPoints` numbers channels `for (int i = 1; i <= numChannels; i++)`, so the
    // controller's own first channel is 1 and a zero is a channel no device has.
    min: {channelId: 1},
    hints: {
      pointType: 'Channel level is the dimming level the channel is driven to, stored as a number. '
        + 'Power-on setting is what the channel does when the controller powers up, stored as on '
        + 'or off.',
      channelId: 'Which channel on the controller, numbered from 1 as the controller numbers them.',
      settable: 'Whether this point can be written from the platform. Every point the gateway '
        + 'creates for a discovered controller is writable; a point that is not can only be read.'
    },
    defaults: {pointType: 'CHANNEL_LEVEL', channelId: 1, settable: true},
    rows: [['pointType', 'channelId']]
  },

  /**
   * A scripting data source: one JavaScript body, the points it reads, and what makes it run.
   *
   * The mirror of `META`. There the script is on the point and the data source is empty; here the
   * script is on the **source** and each point is one variable the script writes. So this layout
   * carries what `META.PL` carries, and `SCRIPTING.PL` is two fields.
   *
   * **`scriptPermissions` is hidden for the same reason as on `META.PL`, and needs one more
   * thing here.** It names the groups the script runs *as*, and an absent value means no groups,
   * which is the confined engine — the right thing for a form reachable from a browser to ask for.
   * But `ScriptDataSourceModel.toVO` calls `new ScriptPermissions(scriptPermissions)` unguarded,
   * and that constructor does `groups.split(",")`, so an **absent or null value is an HTTP 500**
   * rather than a default. Measured twice over: an add omitting the key crashes, and so does a
   * `PATCH` of a source's own unmodified body, because the read hands back
   * `scriptPermissions: null`. `sendEmpty` turns both into `""`. The sibling
   * `MetaPointLocatorModel` already guards this with a null check, which is why `META.PL` needs
   * nothing. Filed as **D101**.
   *
   * **`updateEvent` publishes five values and accepts three.** `commonValidation` switches on it
   * and answers *"Invalid value"* for `NONE` and `CRON` — measured — so the list is narrowed to
   * what the gateway takes. The VO's own initialiser is `UPDATE`, and the REST model leaves the
   * field null, which is refused *"Required value"*; the default carries it.
   *
   * **The rule this form cannot express** is the one on `polling`: a source that is not polling,
   * has no cron pattern and has no context variable flagged for update is refused
   * *"scripting.validate.mustUpdate"* — because nothing would ever run it. That reads three fields,
   * one of them on the points below, and a layout has no word for it. The hint says it instead.
   *
   * `script` is a bare `{"type": "string"}` again, so it needs the same `textarea` `META.PL` needs.
   * It is compiled on every save: a syntax error is a 422 naming `script`.
   */
  'SCRIPTING.DS': {
    hidden: ['scriptPermissions'],
    advanced: ['executionDelaySeconds', 'logLevel', 'logSize', 'logCount', 'historicalSetting'],
    sendEmpty: ['scriptPermissions'],
    types: {script: FormPropertyType.textarea},
    options: {updateEvent: SCRIPTING_UPDATE_EVENTS},
    min: {executionDelaySeconds: 0},
    hints: {
      script: 'JavaScript, compiled by the gateway every time this source is saved — a syntax '
        + 'error is refused here rather than at the next poll. Each point below is a variable this '
        + 'script assigns to.',
      updateEvent: 'Which kind of change to a context point re-runs the script.',
      polling: 'Run the script on the polling interval as well. Something has to make the script '
        + 'run: with this off, the source needs either a cron pattern or a point below with '
        + '"Re-run the script" on, and the gateway refuses it otherwise.',
      historicalSetting: 'Let the script see historical values as well as the current ones.',
      logSize: 'Megabytes per script log file before it is rotated.',
      logCount: 'How many rotated script log files to keep.'
    },
    // What `new ScriptDataSourceVO()` holds. The REST model declares `logSize` and `logCount` as
    // primitives, so a source created without them logs into a 0 MB file and keeps none of them --
    // measured, a POST omitting both reads back `0.0` and `0`.
    defaults: {updateEvent: 'UPDATE', logLevel: 'NONE', logSize: 1, logCount: 5,
      executionDelaySeconds: 0, scriptPermissions: ''},
    rows: [['polling', 'updateEvent'], ['logLevel', 'executionDelaySeconds'],
      ['logSize', 'logCount']]
  },

  /**
   * One variable a script writes, as a point.
   *
   * `varName` is the name the script assigns to, and it carries three separate rules, all in
   * `ScriptingDataSourceDefinition.validate` and all measured: blank is *"Required value"*, a name
   * that is not a JavaScript identifier is *"Invalid value"*, and one already used by another point
   * **or by a context variable on the source** is *"Duplicate variable name: …"*. Only the first is
   * expressible here; the hint carries the other two, because the gateway's refusal arrives after
   * the operator has written the script that uses the name.
   *
   * `dataType` is a real choice on this locator -- `toVO` maps it and an omitted one is refused --
   * unlike `POE_LIGHTING.PL`, where it is derived. IMAGE is narrowed away as everywhere else.
   *
   * `settable` is hidden because `ScriptPointLocatorVO.isSettable()` returns a hard `false` while
   * `toVO` still copies the field, so a submitted `true` is stored where nothing reads it --
   * measured, it reads back `false`. `relinquishable` is not copied at all and reads back null, and
   * `configurationDescription` is the variable name again.
   */
  'SCRIPTING.PL': {
    hidden: ['settable', 'relinquishable', 'configurationDescription'],
    options: {dataType: NON_IMAGE_DATA_TYPES},
    required: ['varName'],
    hints: {
      varName: 'The variable this point is written to in the script. It has to be a valid '
        + 'JavaScript name, and no other point on this source — or context variable on it — may '
        + 'already use it.',
      contextUpdate: 'Re-run the script when this point changes. This is also one of the three '
        + 'things that can make the source run at all, alongside polling and a cron pattern.'
    },
    defaults: {dataType: 'NUMERIC'},
    rows: [['varName', 'dataType']]
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

/**
 * What a new model of this type starts with, as a fresh object every time.
 *
 * A copy rather than the table's own value, because a caller spreads this onto a model an editor then
 * edits. A shallow spread of {@link GatewayFormLayout.defaults} copies its keys and **shares its
 * values**, so `HTTP_RECEIVER.DS`'s seeded `['*.*.*.*']` would be the very same array on every new
 * receiver in the session -- and one editor that mutated it in place would rewrite the table for all
 * of them. ThingsBoard's own array editor rebuilds rather than mutates, so nothing does that today;
 * this is one line for a class of bug that is invisible until it corrupts a form nobody touched.
 */
export const gatewayFormDefaults = (modelType: string,
                                    properties?: FormProperty[]): {[id: string]: any} => {
  const defaults = structuredClone(gatewayFormLayout(modelType)?.defaults ?? {});
  // Every polling source, rather than a line in eighteen layouts and a rule to remember in the rest.
  // `timePeriod` is the one field a polling model cannot do without, and the document says so louder
  // than any other field: `timePeriod` and its own `timePeriodType` are the **only two names that
  // appear in any `required` array in the whole schema** -- 28 sites and 1 -- and it is a delegated
  // fieldset, so an operator who saves without opening it sends `{}` and is refused, while one who
  // fills in the unit and not the count is **accepted with a zero poll period**: nothing validates
  // the count on any type, measured on two (**D81**). `PollingDataSourceVO` starts every subclass on
  // five minutes and no subclass overrides it, so this is the gateway's own default, seeded only where
  // the schema says the field exists.
  //
  // What this closes is the source nobody opened the fieldset on, and only that. An operator who opens
  // it and clears the count still posts `{timePeriod: null, timePeriodType: …}` and still gets a zero
  // period, and **no client-side rule can stop them**: the mapper does mark the fieldset `required`,
  // but a fieldset is delegated to `tb-dynamic-form`, `build` creates no control for it, and the
  // delegate is bound `standalone: true` -- so neither `validate` nor the dialog's save gate ever sees
  // its validity. The rest is the gateway's to fix.
  if (!('timePeriod' in defaults)
      && (properties ?? []).some(property => property.id === 'timePeriod')) {
    defaults.timePeriod = {timePeriod: 5, timePeriodType: 'MINUTES'};
  }
  return defaults;
};
