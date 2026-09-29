// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormPropertyType } from '@shared/models/dynamic-form.models';
import { GATEWAY_FORM_LAYOUTS, gatewayFormDefaults,
  gatewayFormLayout } from '@shared/models/inferrix-gateway-layout.models';

/**
 * The layouts are data, and what makes them right is agreement with the gateway's own Java rather
 * than anything this repository can compile. These lock in the facts that were read out of it, so
 * that a later edit to the table has to be a deliberate one.
 */
describe('gateway form layouts', () => {

  const virtual = GATEWAY_FORM_LAYOUTS['VIRTUAL.PL'];

  it('offers exactly the change types ChangeTypeVO.getChangeTypes admits per data type', () => {
    const table = virtual.gatedOptions.changeType.table;
    const values = (dataType: string) => table[dataType].map(item => item.value);
    expect(values('BINARY'))
      .toEqual(['ALTERNATE_BOOLEAN', 'NO_CHANGE', 'RANDOM_BOOLEAN']);
    expect(values('MULTISTATE'))
      .toEqual(['INCREMENT_MULTISTATE', 'NO_CHANGE', 'RANDOM_MULTISTATE']);
    expect(values('NUMERIC'))
      .toEqual(['BROWNIAN', 'INCREMENT_ANALOG', 'NO_CHANGE', 'RANDOM_ANALOG', 'ANALOG_ATTRACTOR',
        'DECREMENT_ANALOG']);
    expect(values('ALPHANUMERIC')).toEqual(['NO_CHANGE']);
  });

  it('uses INCREMENT_MULTISTATE, not the dead MULTISTATE case the gateway webapp switches on', () => {
    const multistate = virtual.gatedOptions.changeType.table.MULTISTATE.map(item => item.value);
    expect(multistate).toContain('INCREMENT_MULTISTATE');
    expect(multistate).not.toContain('MULTISTATE');
  });

  it('shows each change-specific field for exactly the change types whose VO declares it', () => {
    const shownFor = (id: string) => virtual.visibleWhen[id].values;
    expect(shownFor('values'))
      .toEqual(['INCREMENT_MULTISTATE', 'RANDOM_MULTISTATE']);
    expect(shownFor('roll'))
      .toEqual(['INCREMENT_MULTISTATE', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG']);
    expect(shownFor('min'))
      .toEqual(['BROWNIAN', 'INCREMENT_ANALOG', 'DECREMENT_ANALOG', 'RANDOM_ANALOG']);
    expect(shownFor('max')).toEqual(shownFor('min'));
    expect(shownFor('change')).toEqual(['INCREMENT_ANALOG', 'DECREMENT_ANALOG']);
    expect(shownFor('maxChange')).toEqual(['BROWNIAN', 'ANALOG_ATTRACTOR']);
    expect(shownFor('volatility')).toEqual(['ANALOG_ATTRACTOR']);
    expect(shownFor('attractionPointXid')).toEqual(['ANALOG_ATTRACTOR']);
  });

  it('leaves startValue unconditional, since every change type carries one', () => {
    expect(virtual.visibleWhen.startValue).toBeUndefined();
  });

  it('makes startValue a true/false list for a binary point and free text for the rest', () => {
    const gate = virtual.gatedOptions.startValue;
    expect(gate.by).toBe('dataType');
    expect(gate.table.BINARY.map(item => item.value)).toEqual(['true', 'false']);
    expect(Object.keys(gate.table)).toEqual(['BINARY']);
    expect(gate.unlisted).toBe(FormPropertyType.text);
  });

  it('hides the point-level settable, which the gateway overwrites from the locator', () => {
    expect(GATEWAY_FORM_LAYOUTS.DataPointModel.hidden).toContain('settable');
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL.PL'].hidden).not.toContain('settable');
  });

  it('marks a data source type as worked through even when it needs no overrides', () => {
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL.DS']).toEqual({});
  });

  it('starts a new virtual locator on what VirtualPointLocatorVO starts on', () => {
    expect(virtual.defaults).toEqual({dataType: 'BINARY', changeType: 'ALTERNATE_BOOLEAN'});
  });

  it('defaults only to a value its own gate admits', () => {
    const gate = virtual.gatedOptions.changeType;
    const admitted = gate.table[virtual.defaults[gate.by]].map(item => item.value);
    expect(admitted).toContain(virtual.defaults.changeType);
  });

  it('answers nothing for a prototype key a gateway could name a model type', () => {
    ['constructor', 'toString', 'hasOwnProperty', 'valueOf'].forEach(key =>
      expect(gatewayFormLayout(key)).withContext(key).toBeUndefined());
    expect(gatewayFormLayout('VIRTUAL.PL')).toBe(GATEWAY_FORM_LAYOUTS['VIRTUAL.PL']);
    expect(gatewayFormLayout(undefined)).toBeUndefined();
  });

  it('narrows only fields the renderer lays out itself', () => {
    // A layout may only put an option list on a scalar. `GatewayFormComponent.build` decides which
    // properties get a control from the schema's own type, so naming an array or a nested object
    // here would ask its template for a control that was never created.
    const scalars = new Set(['dataType', 'changeType', 'startValue', 'min', 'max', 'change',
      'maxChange', 'volatility', 'attractionPointXid', 'roll', 'settable',
      // Modbus. `bit` is a scalar despite the schema calling it `{string, format: byte}`: that is
      // springdoc's rendering of the Java `byte`, and the wire format is a plain number.
      'transportType', 'modbusDataType', 'bit', 'charset',
      // Modbus serial. Every one is a `String` on the REST model; `baudRate` is a plain `int`.
      'baudRate', 'flowControlIn', 'flowControlOut', 'dataBits', 'stopBits', 'parity',
      'encoding',
      // BACnet. The two lookups are narrowed by a component rather than by a layout.
      'writePriority',
      // SNMP. The first two are `String` on the REST model, mapped through a code table rather than
      // an enum; the two protocols are real enums, relabelled rather than narrowed.
      'snmpVersion', 'setType', 'authProtocol', 'privProtocol',
      // Meta. Both are `String` on the REST model over an `ExportCodes` table, relabelled in the
      // gateway's own words rather than narrowed.
      'updateEvent', 'contextUpdateEvent',
      // MQTT. All four are `String` on the REST model over a Java enum, relabelled because the
      // gateway registers message keys its own property file does not carry.
      'qosType', 'publishQosType', 'publishTopicType', 'subscribeTopicType',
      // Mesh controller. A `String` on the REST model over an `ExportCodes` table, written out here
      // rather than narrowed from the schema -- the schema says `string` and the gateway's own check
      // reads a table 34 classes share (D90).
      'attributeId',
      // PoE lighting. A `String` on the REST model over a Java enum, written out here rather than
      // narrowed: the schema publishes a bare `string` and `toVO` calls `valueOf` on it unguarded.
      'pointType',
      // System attributes. A `String` on the REST model over an `ExportCodes` table, where an
      // unrecognised name resolves to -1 and skips validation rather than failing it (D105).
      'attributeType',
      // Current sensor. Both are real enums in the schema, so the renderer already narrows them;
      // these lists relabel (`PHASE_1` reads as Phase 1, `32_A` as 32 A) and, for `ctId`, reorder
      // by rating. Same membership as the schema publishes -- neither drops a value.
      'phaseId', 'ctId']);
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      [...Object.keys(layout.options ?? {}), ...Object.keys(layout.gatedOptions ?? {})]
        .forEach(id => expect(scalars.has(id)).withContext(`${modelType}.${id}`).toBe(true));
    });
  });

  // --- the ten mirrored mesh node types ---------------------------------------------------------

  const meshNodeTypes = ['BACNET_IP_MESH_NODE', 'BACNET_MSTP_MESH_NODE', 'MESH_EXTENDER_MESH_NODE',
    'META_MESH_NODE', 'MODBUS_MESH_NODE', 'POE_LIGHTING_MESH_NODE', 'SNMP_MESH_NODE',
    'STUDENT_ASSET_TAG_MESH_NODE', 'VIRTUAL_MESH_NODE'];

  it('locks all four fields a mesh node reports, settable included', () => {
    // `attributeId` and `type` are the node's own attribute and its wire encoding, `dataType` is how
    // the gateway stores it. A node reports all three; it does not take them.
    //
    // `settable` is the fourth, and row 20 briefly got this wrong by making it editable. The
    // argument was that every `Create*MeshNode*VO` hardcodes `setSettable(false)`, so nothing else
    // could turn it on -- but that path creates only the heartbeat point. The mirrored points come
    // from the runtime, and eight of the ten RTs call `locatorVO.setSettable(data.isSettable())`,
    // which is the radio deciding it. Measured: 18 of the 57 live `*_MESH_NODE.PL` points carry
    // `settable: true`, every one a digital output.
    //
    // Editable was destructive as well as wrong: the dialog posts the whole locator and `toVO`
    // copies the field, so a `PUT` with `false` permanently clobbers a `true` the radio set --
    // measured 200, reads back false, and nothing re-creates a point that still exists. Read-only
    // round-trips the stored value instead. The flag also picks the BACnet object type the gateway
    // republishes to third-party clients, so the blast radius is wider than this form.
    meshNodeTypes.forEach(type => {
      const point = GATEWAY_FORM_LAYOUTS[`${type}.PL`];
      expect(point.readonly).withContext(type)
        .toEqual(['attributeId', 'dataType', 'type', 'settable']);
      expect(point.hidden).withContext(type).toContain('relinquishable');
      // Shown, because the flag is worth reading; explained, because a disabled box needs a reason.
      expect(point.hidden).withContext(type).not.toContain('settable');
      expect(point.hints.settable).withContext(type).toBeTruthy();
    });
  });

  it('gives all ten mesh node sources one shape, and nine sources eight locators', () => {
    // Verified against the schema document with `allOf` resolved: all ten sources declare
    // `controllerAddress` and `publisherId` and nothing else over the common eleven. Both are
    // read-only because the pair is the key every runtime matches an incoming frame against.
    //
    // Ten sources, nine locators: MODBUS_IP_MESH_NODE.DS and MODBUS_SERIAL_MESH_NODE.DS share
    // MODBUS_MESH_NODE.PL.
    const sources = Object.keys(GATEWAY_FORM_LAYOUTS)
      .filter(modelType => modelType.endsWith('_MESH_NODE.DS'));
    expect(sources.length).toBe(10);
    expect(meshNodeTypes.length).toBe(9);
    sources.forEach(modelType => {
      const layout = GATEWAY_FORM_LAYOUTS[modelType];
      expect(layout.provisionedPoints).withContext(modelType).toBe(true);
      expect(layout.readonly).withContext(modelType).toEqual(['controllerAddress', 'publisherId']);
      expect(layout.rows).withContext(modelType).toEqual([['controllerAddress', 'publisherId']]);
      // The only thing that varies is the noun, and every one of them names something.
      expect(layout.hints.controllerAddress).withContext(modelType)
        .toMatch(/^The mesh controller this .+ node reports through\./);
    });
    // A fresh object per type, so a mutation of one cannot reach another.
    expect(new Set(sources.map(modelType => GATEWAY_FORM_LAYOUTS[modelType])).size)
      .toBe(sources.length);
  });

  it('offers no Add on a source whose points the gateway provisions', () => {
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL_MESH_NODE.DS'].provisionedPoints).toBe(true);
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL_MESH_NODE.DS'].readonly)
      .toEqual(['controllerAddress', 'publisherId']);
    // A virtual source's points are hand-made, so its Add button stays.
    expect(GATEWAY_FORM_LAYOUTS['VIRTUAL.DS'].provisionedPoints).toBeUndefined();
  });

  it('never both reads a field back and hides it', () => {
    // A hidden field has no control to disable, so naming it in both says one of the two is wrong.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      (layout.readonly ?? []).forEach(id =>
        expect(hidden.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('never asks a provisioned point for something an Add form could not supply', () => {
    // This spec used to assert that a provisioned locator has no editable field at all, on the
    // reasoning that an Add form would otherwise take no input. That rule was already false when it
    // was written -- `MODBUS_CONTROLLER.PL` leaves `settable` editable and its source is
    // provisioned -- and it is the wrong rule anyway: `provisionedPoints` removes the Add button, so
    // there is no form to be empty. An editable field on a provisioned point is an *edit*, which is
    // the only thing these forms are for.
    //
    // What does have to hold is the pairing rule, because a locator that is `required` somewhere it
    // cannot be filled is unsubmittable rather than merely odd: a disabled control is left out of
    // Angular's validation entirely, and a provisioned point has no Add path to seed a default from.
    const provisioned = Object.keys(GATEWAY_FORM_LAYOUTS)
      .filter(modelType => GATEWAY_FORM_LAYOUTS[modelType].provisionedPoints)
      .map(modelType => modelType.replace(/\.DS$/, '.PL'))
      .filter(modelType => GATEWAY_FORM_LAYOUTS[modelType]);
    expect(provisioned.length).toBeGreaterThan(20);
    provisioned.forEach(modelType => {
      const layout = GATEWAY_FORM_LAYOUTS[modelType];
      (layout.required ?? []).forEach(id => {
        expect((layout.readonly ?? []).includes(id)).withContext(`${modelType}.${id}`).toBe(false);
        expect((layout.hidden ?? []).includes(id)).withContext(`${modelType}.${id}`).toBe(false);
      });
    });
  });

  const modbus = GATEWAY_FORM_LAYOUTS['MODBUS.PL'];

  it('offers exactly the 32 codes ModbusPointLocatorVO.MODBUS_DATA_TYPE_CODES declares', () => {
    // The gateway's `validate()` rejects anything its table does not name, so a value missing here
    // is a type an operator cannot choose and a value invented here is a guaranteed 422.
    const types = modbus.gatedOptions.modbusDataType.table.HOLDING_REGISTER.map(item => item.value);
    expect(types.length).toBe(32);
    expect(types).toContain('FOUR_BYTE_FLOAT');
    expect(types).toContain('EIGHT_BYTE_MOD_10K_SWAPPED');
    expect(types).toContain('ONE_BYTE_INT_UNSIGNED_UPPER');
    expect(new Set(types).size).toBe(types.length);
  });

  it('admits only BINARY on the two ranges modbus4j refuses a numeric locator for', () => {
    // `NumericLocator.validate()`: "Only binary values can be read from Coil and Input ranges".
    const values = (range: string) =>
      modbus.gatedOptions.modbusDataType.table[range].map(item => item.value);
    expect(values('COIL_STATUS')).toEqual(['BINARY']);
    expect(values('INPUT_STATUS')).toEqual(['BINARY']);
    expect(values('HOLDING_REGISTER').length).toBe(32);
    expect(values('INPUT_REGISTER').length).toBe(32);
  });

  it('shows writeType for exactly the ranges settableRange() admits', () => {
    // `ModbusPointLocatorVO.settableRange()` is `range == 1 || range == 3`, which are COIL_STATUS
    // and HOLDING_REGISTER -- the two a Modbus master may write.
    expect(modbus.visibleWhen.writeType).toEqual({by: 'range',
      values: ['COIL_STATUS', 'HOLDING_REGISTER']});
  });

  it('scales only the data types getDataTypeId() decodes as a number', () => {
    const numeric = modbus.visibleWhen.multiplier.values;
    expect(numeric).not.toContain('BINARY');
    expect(numeric).not.toContain('CHAR');
    expect(numeric).not.toContain('VARCHAR');
    expect(numeric).toContain('FOUR_BYTE_FLOAT');
    expect(modbus.visibleWhen.additive.values).toEqual(numeric);
    expect(modbus.visibleWhen.multistateNumeric.values).toEqual(numeric);
  });

  it('shows the string fields for exactly the two types isString() names', () => {
    expect(modbus.visibleWhen.registerCount).toEqual({by: 'modbusDataType',
      values: ['CHAR', 'VARCHAR']});
    expect(modbus.visibleWhen.charset).toEqual(modbus.visibleWhen.registerCount);
  });

  it('bounds bit to the range ModbusUtils.validateBit accepts, as numbers', () => {
    // The schema types the Java `byte` as a base64 string, so the field arrives as free text; the
    // device throws "Invalid bit" outside 0-15 and Jackson wants a number, not "3".
    const bits = modbus.options.bit;
    expect(bits.map(item => item.value)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
      15]);
    bits.forEach(item => expect(typeof item.value).toBe('number'));
  });

  it('offers only charsets Charset.forName can resolve, and not the gateway\'s RTU', () => {
    const charsets = modbus.options.charset.map(item => item.value);
    expect(charsets).toContain('ASCII');
    expect(charsets).not.toContain('RTU');
    charsets.forEach(name => expect(name).toMatch(/^(ASCII|UTF-8|UTF-16(BE|LE)|ISO-8859-1)$/));
  });

  it('hides every Modbus locator field the gateway derives or ignores', () => {
    // `dataType`/`settable` are computed by the VO, `rangeId`/`modbusDataTypeId` are derived
    // getters no setter reads, and `toVO()` never touches `relinquishable`. A control for any of
    // them would be one the operator changes and the device discards.
    ['dataType', 'settable', 'relinquishable', 'rangeId', 'modbusDataTypeId']
      .forEach(id => expect(modbus.hidden).toContain(id));
  });

  it('starts a new Modbus locator on what ModbusPointLocatorVO starts on', () => {
    // The model's own `range` and `modbusDataType` are null until set and `validate()` rejects
    // both, so an add with no defaults cannot be saved at all.
    expect(modbus.defaults).toEqual({range: 'COIL_STATUS', modbusDataType: 'BINARY'});
    const admitted = modbus.gatedOptions.modbusDataType.table[modbus.defaults.range as string];
    expect(admitted.map(item => item.value)).toContain(modbus.defaults.modbusDataType);
  });

  it('keeps the Modbus/IP connection on the form and the per-device limits under Advanced', () => {
    const layout = GATEWAY_FORM_LAYOUTS['MODBUS_IP.DS'];
    const advanced = new Set(layout.advanced);
    ['transportType', 'host', 'port', 'encapsulated', 'timePeriod', 'timeout', 'retries',
      'createSlaveMonitorPoints'].forEach(id => expect(advanced.has(id)).withContext(id).toBe(false));
    ['maxReadBitCount', 'lingerTime', 'scaleFactor', 'maxConcurrentConnections', 'logIO']
      .forEach(id => expect(advanced.has(id)).withContext(id).toBe(true));
  });

  it('keeps the transport acronyms rather than humanising them', () => {
    const items = GATEWAY_FORM_LAYOUTS['MODBUS_IP.DS'].options.transportType;
    expect(items.map(item => item.value)).toEqual(['TCP', 'TCP_KEEP_ALIVE', 'UDP']);
    expect(items[0].label).toBe('TCP');
    expect(items[2].label).toBe('UDP');
  });

  it('offers exactly the serial line values the com.inferrix.serial enums declare', () => {
    const options = GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].options;
    const values = (id: string) => options[id].map(item => item.value);
    expect(values('flowControlIn')).toEqual(['NONE', 'RTSCTS', 'XONXOFF']);
    expect(values('flowControlOut')).toEqual(values('flowControlIn'));
    expect(values('dataBits'))
      .toEqual(['DATA_BITS_5', 'DATA_BITS_6', 'DATA_BITS_7', 'DATA_BITS_8']);
    expect(values('stopBits')).toEqual(['STOP_BITS_1', 'STOP_BITS_1_5', 'STOP_BITS_2']);
    expect(values('parity')).toEqual(['NONE', 'ODD', 'EVEN', 'MARK', 'SPACE']);
    expect(values('encoding')).toEqual(['RTU', 'ASCII']);
  });

  it('labels the line settings by what they are, not by their Java constants', () => {
    const labels = (id: string) =>
      GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].options[id].map(item => item.label);
    // The two the gateway's own dropdowns show raw, and the two humanise would ruin.
    expect(labels('dataBits')).toEqual(['5', '6', '7', '8']);
    expect(labels('stopBits')).toEqual(['1', '1.5', '2']);
    expect(labels('flowControlIn')).toEqual(['None', 'RTS/CTS', 'XON/XOFF']);
    expect(labels('encoding')).toEqual(['RTU', 'ASCII']);
  });

  it('offers baud rates as numbers, since the model holds an int', () => {
    const items = GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].options.baudRate;
    expect(items.length).toBe(13);
    items.forEach(item => expect(typeof item.value).toBe('number'));
    expect(items[0].value).toBe(110);
    expect(items[items.length - 1].value).toBe(921600);
  });

  it('defaults the line settings the VO does, and the one it leaves null', () => {
    // Not a convenience. `toVO` converts each of these with `Enum.valueOf` before anything
    // validates, so an absent one is a null-pointer exception on the gateway. Five are what
    // `ModbusSerialDataSourceVO`'s constructor sets; `encoding` it leaves null, so RTU here is
    // this layout's choice and the reason a serial source can be saved at all.
    const layout = GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'];
    expect(layout.defaults).toEqual({baudRate: 9600, flowControlIn: 'NONE', flowControlOut: 'NONE',
      dataBits: 'DATA_BITS_8', stopBits: 'STOP_BITS_1', parity: 'NONE', encoding: 'RTU'});
    // And each one has to be a value its own list offers, or the select opens on nothing.
    Object.entries(layout.defaults).forEach(([id, value]) =>
      expect(layout.options[id].some(item => item.value === value))
        .withContext(`${id} = ${value}`).toBe(true));
  });

  it('keeps the serial line on the form and the poll tuning under Advanced', () => {
    const advanced = new Set(GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].advanced);
    ['commPortId', 'baudRate', 'dataBits', 'stopBits', 'parity', 'encoding', 'timePeriod',
      'timeout', 'retries'].forEach(id => expect(advanced.has(id)).withContext(id).toBe(false));
    ['maxReadBitCount', 'logIO', 'discardDataDelay', 'flowControlIn', 'flowControlOut', 'echo']
      .forEach(id => expect(advanced.has(id)).withContext(id).toBe(true));
  });

  it('carries none of the socket settings a serial line has no socket for', () => {
    const named = new Set([...(GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].advanced ?? []),
      ...Object.keys(GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.DS'].options ?? {})]);
    ['host', 'port', 'transportType', 'encapsulated', 'lingerTime', 'scaleFactor',
      'maxBackOffPeriod', 'maxConcurrentConnections']
      .forEach(id => expect(named.has(id)).withContext(id).toBe(false));
  });

  it('shares MODBUS.PL with the IP source rather than restating its rules', () => {
    // `/v2/data-source-types` answers MODBUS.PL for both, so a second locator layout here would
    // be a copy that could drift. Its absence is the assertion.
    expect(GATEWAY_FORM_LAYOUTS['MODBUS_SERIAL.PL']).toBeUndefined();
    expect(GATEWAY_FORM_LAYOUTS['MODBUS.PL']).toBeDefined();
  });

  it('hides the two BACnet locator fields the gateway derives or never reads', () => {
    // `configurationDescription` is the gateway's own rendering of the fields above it, and
    // `BACnetPointLocatorModel.toVO` sets ten fields, of which `relinquishable` is not one.
    expect(GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].hidden)
      .toEqual(['relinquishable', 'configurationDescription']);
  });

  it('bounds BACnet write priority to the 1-16 validate() accepts, as numbers', () => {
    const items = GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].options.writePriority;
    expect(items.length).toBe(16);
    items.forEach(item => expect(typeof item.value).toBe('number'));
    expect(items[0].value).toBe(1);
    expect(items[15].value).toBe(16);
    // Named, because which end is strongest is the one thing an operator cannot guess.
    expect(items[0].label).toBe('1 (highest)');
    expect(items[15].label).toBe('16 (lowest)');
  });

  it('shows write priority only on a point that can be written', () => {
    const gate = GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].visibleWhen.writePriority;
    expect(gate.by).toBe('settable');
    // A toggle's value, not its label -- `visible()` compares with `includes` against the control.
    expect(gate.values).toEqual([true]);
  });

  it('scales only a numeric BACnet point, which is the only branch that applies it', () => {
    const shownFor = (id: string) => GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].visibleWhen[id];
    expect(shownFor('multiplier').by).toBe('dataType');
    expect(shownFor('multiplier').values).toEqual(['NUMERIC']);
    expect(shownFor('additive')).toEqual(shownFor('multiplier'));
  });

  it('starts a BACnet point on a combination the gateway accepts', () => {
    const defaults = GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].defaults;
    expect(defaults).toEqual({objectTypeId: 'ANALOG_INPUT', propertyIdentifierId: 'present-value',
      dataType: 'NUMERIC', multiplier: 1, writePriority: 16});
    // The two that are not cosmetic. An absent `propertyIdentifierId` is a null-pointer exception
    // in `toVO`; an absent `multiplier` is 0, and 0 multiplies every reading it ever takes.
    expect(defaults.propertyIdentifierId).toBe('present-value');
    expect(defaults.multiplier).toBe(1);
    expect(GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'].options.writePriority
      .some(item => item.value === defaults.writePriority)).toBe(true);
  });

  it('starts a BACnet data source on the COV timeout its own VO starts on', () => {
    // `BACnetDataSourceModel` declares a bare int, so an absent key is 0 and `validate` rejects
    // anything below 1 -- the VO's own 60 is unreachable through REST without this.
    expect(GATEWAY_FORM_LAYOUTS['BACNET_IP.DS'].defaults)
      .toEqual({covSubscriptionTimeoutMinutes: 60});
    expect(GATEWAY_FORM_LAYOUTS['BACNET_IP.DS'].rows)
      .toEqual([['localDeviceConfig', 'covSubscriptionTimeoutMinutes']]);
  });

  it('leaves the two lookup fields to the component rather than listing them', () => {
    // A layout is a constant; the object types and their properties are HTTP. Naming either here
    // would be a list that goes stale against the gateway it is meant to describe.
    const layout = GATEWAY_FORM_LAYOUTS['BACNET_IP.PL'];
    expect(layout.options.objectTypeId).toBeUndefined();
    expect(layout.options.propertyIdentifierId).toBeUndefined();
    expect(GATEWAY_FORM_LAYOUTS['BACNET_IP.DS'].options).toBeUndefined();
  });

  it('gives MS/TP the same form as BACnet/IP, because it is the same model', () => {
    // `BACnetMstpDataSourceModel` and `BACnetMstpPointLocatorModel` add nothing to their bases,
    // and the live schema agrees field for field. The serial settings an operator looks for here
    // are on the local device, not on the data source.
    expect(GATEWAY_FORM_LAYOUTS['BACNET_MSTP.PL']).toBe(GATEWAY_FORM_LAYOUTS['BACNET_IP.PL']);
    const mstp = GATEWAY_FORM_LAYOUTS['BACNET_MSTP.DS'];
    const ip = GATEWAY_FORM_LAYOUTS['BACNET_IP.DS'];
    expect(mstp.defaults).toEqual(ip.defaults);
    expect(mstp.rows).toEqual(ip.rows);
  });

  it('names the locator type the gateway will not name for MS/TP', () => {
    // `/v2/data-source-types` answers null for this one type, measured on 5.1.0 and 5.1.1. Without
    // it a source with no points has nothing to build its first point's locator form from.
    expect(GATEWAY_FORM_LAYOUTS['BACNET_MSTP.DS'].pointLocatorType).toBe('BACNET_MSTP.PL');
    // And only for that one: everywhere else the gateway's own answer is the answer.
    Object.entries(GATEWAY_FORM_LAYOUTS)
      .filter(([modelType]) => modelType !== 'BACNET_MSTP.DS')
      .forEach(([modelType, layout]) =>
        expect(layout.pointLocatorType).withContext(modelType).toBeUndefined());
  });

  const metaPoint = GATEWAY_FORM_LAYOUTS['META.PL'];

  it('gives a meta script the box a script needs, which the schema cannot ask for', () => {
    // Declared `{"type": "string"}` with no `format`, so the mapper's single-line text box is the
    // right reading of the schema and the wrong control for a JavaScript body.
    expect(metaPoint.types.script).toBe(FormPropertyType.textarea);
  });

  it('offers the eight update events MetaPointLocatorVO registers, NONE first', () => {
    // `UPDATE_EVENT_CODES` in registration order. `NONE` shares its id (0) with
    // `UPDATE_EVENT_CONTEXT_UPDATE` and is labelled `dsEdit.meta.event.context`, which is why it
    // reads "Context update" rather than "None" -- a point on it runs when its context does.
    expect(metaPoint.options.updateEvent.map(item => item.value))
      .toEqual(['NONE', 'MINUTES', 'HOURS', 'DAYS', 'WEEKS', 'MONTHS', 'YEARS', 'CRON']);
    expect(metaPoint.options.updateEvent[0].label).toBe('Context update');
    expect(metaPoint.options.contextUpdateEvent.map(item => item.value))
      .toEqual(['CONTEXT_UPDATE', 'CONTEXT_CHANGE', 'CONTEXT_LOGGED']);
  });

  it('asks for a cron pattern on CRON and on nothing else', () => {
    // The only one of the eight that brings a field with it. `validate` parses the pattern only on
    // CRON -- measured: junk in it is accepted while the event is NONE.
    expect(metaPoint.visibleWhen).toEqual({updateCronPattern: {by: 'updateEvent', values: ['CRON']}});
  });

  it('hides the meta locator fields the gateway derives, ignores or decides', () => {
    // `isSettable()` returns a hard false, `toVO` never reads `relinquishable`,
    // `getConfigurationDescription()` is the script's first 40 characters, and `scriptEngine` is a
    // one-value enum. `scriptPermissions` is hidden so the form cannot ask for an unconfined engine.
    expect(metaPoint.hidden).toEqual(['settable', 'relinquishable', 'configurationDescription',
      'scriptEngine', 'scriptPermissions']);
  });

  it('sends the two fields whose absent value is -1 rather than a default', () => {
    // Both map through `ExportCodes.getId`, which answers -1 for a value it cannot find -- and
    // `getId(null)` finds nothing. Measured on 5.1.x: a locator posted without either is accepted
    // 201 and reads back with `dataType: null` and `scriptEngine: null`. Hiding `scriptEngine`
    // therefore means defaulting it, not dropping it.
    expect(metaPoint.defaults.scriptEngine).toBe('JAVASCRIPT');
    expect(metaPoint.defaults.dataType).toBe('NUMERIC');
    expect(metaPoint.hidden).toContain('scriptEngine');
  });

  it('starts a meta point on what MetaPointLocatorVO starts on', () => {
    // The VO's own field initialisers, which Jackson applies only where the key is absent:
    // `variableName = "my"`, `logLevel = NONE`, `logSize = 1.0F`, `logCount = 5`,
    // `contextUpdateEvent = 0` (CONTEXT_UPDATE). All four are defaultable on 5.1.x and seeded here
    // so the form shows what the gateway would have chosen.
    expect(metaPoint.defaults.variableName).toBe('my');
    expect(metaPoint.defaults.logLevel).toBe('NONE');
    expect(metaPoint.defaults.logSize).toBe(1);
    expect(metaPoint.defaults.logCount).toBe(5);
    expect(metaPoint.defaults.contextUpdateEvent).toBe('CONTEXT_UPDATE');
    // Not defaultable, and the one the whole form hangs off: an absent `updateEvent` is refused
    // with "Invalid value", so it is seeded whatever else is.
    expect(metaPoint.defaults.updateEvent).toBe('NONE');
  });

  it('keeps the script and its trigger on the form and the log under Advanced', () => {
    expect(metaPoint.advanced)
      .toEqual(['executionDelaySeconds', 'logLevel', 'logSize', 'logCount']);
    expect(metaPoint.rows.flat()).not.toContain('script');
  });

  it('gives the meta data source nothing to arrange', () => {
    // Every field it declares is one the platform strips: identity, the descriptions, and the four
    // the points table owns. Present so the type counts as worked through.
    expect(GATEWAY_FORM_LAYOUTS['META.DS']).toEqual({});
  });

  const snmp = GATEWAY_FORM_LAYOUTS['SNMP.DS'];
  const snmpPoint = GATEWAY_FORM_LAYOUTS['SNMP.PL'];

  it('offers the three SNMP versions the definition accepts, and no fourth', () => {
    // `SnmpVersion` declares v1(0), v2c(1), v3(3) and `SnmpDataSourceDefinition.validate` accepts
    // those three ids alone. The spelling is what `SnmpSettings.getSnmpVersionId` matches on.
    expect(snmp.options.snmpVersion.map(item => item.value)).toEqual(['v1', 'v2c', 'v3']);
  });

  it('asks for a community string on v1 and v2c, and for a user on v3', () => {
    // The branch in `SnmpDataSourceDefinition.validate`: readCommunity either side of it, and
    // securityName / contextName / the two protocols only under v3.
    expect(snmp.visibleWhen.readCommunity).toEqual({by: 'snmpVersion', values: ['v1', 'v2c']});
    expect(snmp.visibleWhen.writeCommunity).toEqual({by: 'snmpVersion', values: ['v1', 'v2c']});
    ['securityName', 'contextName', 'engineId', 'contextEngineId', 'authProtocol', 'privProtocol',
      'authPassphrase', 'privPassphrase']
      .forEach(id => expect(snmp.visibleWhen[id]).withContext(id)
        .toEqual({by: 'snmpVersion', values: ['v3']}));
  });

  it('sends neither v3 optional field, because stack 5.1.3 accepts both absent and blank', () => {
    // It used to need a `sendEmpty: ['contextName']` escape hatch: absent was 422 "Required value"
    // while `""` was 201, and `engineId` refused the opposite -- absent fine, `""` a 422
    // `validate.minLength`. D69 made blank count as absent on both, measured 201 with the pair
    // omitted and 201 with both `""`. That was the mechanism's only user, so the mechanism went with
    // it; `(snmp as any).sendEmpty` is checked here rather than typed because the key no longer
    // exists on the interface.
    expect((snmp as any).sendEmpty).toBeUndefined();
    // The two passphrases now carry the rule the gateway enforces (D70): a protocol other than NONE
    // requires one. Not `required`, because both are gated -- a closed gate with a required empty
    // control would dead-end the save with nothing on screen to fix.
    expect(snmp.hints.authPassphrase).toContain('Required');
    expect(snmp.hints.privPassphrase).toContain('Required');
    expect(snmp.required).toBeUndefined();
  });

  const mqtt = GATEWAY_FORM_LAYOUTS['MQTT.DS'];
  const mqttPoint = GATEWAY_FORM_LAYOUTS['MQTT.PL'];

  it('offers the three QoS levels VALID_TYPES admits, and not the one a broker answers with', () => {
    // `QosType` declares FAILURE(128) and leaves it out of `VALID_TYPES`; `validate` refuses it on
    // both models. The constant is `ATLEAST_ONCE`, without the second underscore.
    [mqtt.options.qosType, mqttPoint.options.publishQosType].forEach(items => {
      expect(items.map(item => item.value))
        .toEqual(['AT_MOST_ONCE', 'ATLEAST_ONCE', 'EXACTLY_ONCE']);
      expect(items.map(item => item.value)).not.toContain('FAILURE');
    });
  });

  it('offers the four topic types VALID_TYPES admits, and not NONE', () => {
    [mqttPoint.options.publishTopicType, mqttPoint.options.subscribeTopicType].forEach(items =>
      expect(items.map(item => item.value))
        .toEqual(['PLAIN', 'JSON', 'JSON_WITH_TIMESTAMP', 'INFERRIX_JSON']));
  });

  it('seeds every MQTT enum, because toVO resolves each one with valueOf', () => {
    // `QosType.valueOf(null)` and `DataSourceTopicType.valueOf(null)` throw, and `toVO` runs before
    // `validate` -- so an absent one is a 500 rather than a message. Measured on 5.1.x.
    expect(mqtt.defaults.qosType).toBe('ATLEAST_ONCE');
    expect(mqttPoint.defaults.publishQosType).toBe('ATLEAST_ONCE');
    expect(mqttPoint.defaults.publishTopicType).toBe('INFERRIX_JSON');
    expect(mqttPoint.defaults.subscribeTopicType).toBe('INFERRIX_JSON');
    expect(mqttPoint.defaults.dataType).toBe('NUMERIC');
  });

  it('refuses to submit a broker URI or a topic filter the gateway cannot parse', () => {
    // An absent or empty `brokerUri` is a 500 inside `validateURI`; `topicFilters` is a clean 422 and
    // is required so the operator hears it before the round trip.
    expect(mqtt.required).toEqual(['brokerUri', 'topicFilters']);
    expect(mqttPoint.required).toEqual(['publishTopic', 'subscribeTopic']);
  });

  it('never names a field in both required and advanced', () => {
    // The Advanced panel is collapsed, so a required field inside it blocks a save with nothing on
    // screen to explain why.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const advanced = new Set(layout.advanced ?? []);
      (layout.required ?? []).forEach(id =>
        expect(advanced.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  const receiver = GATEWAY_FORM_LAYOUTS['HTTP_RECEIVER.DS'];
  const receiverPoint = GATEWAY_FORM_LAYOUTS['HTTP_RECEIVER.PL'];

  it('hands out a fresh copy of the defaults, never the table\'s own values', () => {
    // A caller spreads these onto a model an editor edits, and a shallow spread shares the values.
    // Without the copy the seeded array would be the same instance on every new receiver.
    const first = gatewayFormDefaults('HTTP_RECEIVER.DS');
    const second = gatewayFormDefaults('HTTP_RECEIVER.DS');
    expect(first).toEqual(second);
    expect(first.ipWhiteList).not.toBe(second.ipWhiteList);
    expect(first.ipWhiteList).not.toBe(GATEWAY_FORM_LAYOUTS['HTTP_RECEIVER.DS'].defaults.ipWhiteList);
    first.ipWhiteList.push('10.0.0.1');
    expect(GATEWAY_FORM_LAYOUTS['HTTP_RECEIVER.DS'].defaults.ipWhiteList).toEqual(['*.*.*.*']);
    // And an answer for a type with no entry, since every add site calls this unconditionally.
    expect(gatewayFormDefaults('NO_SUCH.DS')).toEqual({});
    expect(gatewayFormDefaults('constructor')).toEqual({});
  });

  it('seeds both receiver lists on what the VO starts on, because an absent one is a 500', () => {
    // `validate` iterates each array, and `toVO` copies the model's field straight across -- so an
    // omitted list throws inside the validator. `HttpReceiverDataSourceVO` starts on these two.
    expect(receiver.defaults).toEqual({ipWhiteList: ['*.*.*.*'], deviceIdWhiteList: ['*']});
  });

  it('asks a receiver point for the parameter it reads, and nothing else', () => {
    // `parameterName` is the whole locator and `validate` refuses an empty one. `dataType` is
    // seeded for convenience: this validator checks it, so an absent one is a 422 rather than -1.
    expect(receiverPoint.required).toEqual(['parameterName']);
    expect(receiverPoint.defaults.dataType).toBe('NUMERIC');
    expect(receiverPoint.hidden).toEqual(['settable', 'relinquishable', 'configurationDescription']);
  });

  it('shows binary0Value only on a binary point, on both types that carry it', () => {
    // `HttpReceiverDataSourceRT` and `SnmpPointLocatorRT` each read it only when the data type is
    // BINARY, and parse the raw value by type otherwise.
    [receiverPoint, snmpPoint].forEach(layout =>
      expect(layout.visibleWhen.binary0Value).toEqual({by: 'dataType', values: ['BINARY']}));
    expect(receiverPoint.defaults.binary0Value).toBe('0');
  });

  it('never makes a gated field required', () => {
    // A gate hides the row and keeps the control, validator included. A required field whose gate is
    // closed leaves the save blocked by a control that is not on screen, with nothing to fill in.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const gated = new Set([...Object.keys(layout.visibleWhen ?? {})]);
      (layout.required ?? []).forEach(id =>
        expect(gated.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('narrows the data type away from IMAGE wherever the runtime cannot make one', () => {
    // `SnmpPointLocatorRT.variableToValue` and `HttpJsonRetrieverPointLocatorRT.parseValue` end their
    // switch on `default: throw`, and `JavaScriptService.coerce` ends its chain the same way -- and
    // nothing validates `dataType` on any of the three, so an IMAGE point saves 201 and then fails
    // every poll for ever. `HTTP_RECEIVER.PL` is deliberately absent: `PointValue.stringToValue` has a
    // real `DataTypes.IMAGE` branch, so a receiver point can carry one.
    // `INTERNAL.PL` joined the list once its data type started reaching storage at all (D84):
    // `InternalDataSourceRT.doPoll` writes a monitor value through a `Number` branch and a `String`
    // branch and has nothing that can produce an image. Measured 201, so this one is ours to refuse --
    // filed as D89.
    ['VIRTUAL.PL', 'META.PL', 'SNMP.PL', 'HTTP_JSON_RETRIEVER.PL', 'INTERNAL.PL'].forEach(modelType =>
      expect(GATEWAY_FORM_LAYOUTS[modelType].options.dataType.map(item => item.value))
        .withContext(modelType)
        .toEqual(['BINARY', 'MULTISTATE', 'NUMERIC', 'ALPHANUMERIC']));
    expect(GATEWAY_FORM_LAYOUTS['HTTP_RECEIVER.PL'].options).toBeUndefined();
  });

  it('never types a field as a password, because that is the schema\'s word', () => {
    // `keep()` builds its set of secrets from the *mapper's* properties, where a `password` type means
    // the schema marked the field `writeOnly` -- it never sees `layout.types`. So a layout typing a
    // field as a password would render it masked and get none of the empty-drop protection that makes
    // masking safe, which is a worse position than either honest one. `HTTP_JSON_RETRIEVER.DS`'s
    // bearer token was exactly that mistake, and is a textarea for it.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) =>
      Object.entries(layout.types ?? {}).forEach(([id, type]) =>
        expect(type).withContext(`${modelType}.${id}`).not.toBe(FormPropertyType.password)));
  });

  it('gives a number a floor where required would count zero as an answer', () => {
    // The gateway wants `timeoutSeconds > 0` and `retries >= 0`, and Angular's `required` is satisfied
    // by a typed 0 -- which for the timeout is the one value whose refusal names another field.
    expect(retriever.min).toEqual({timeoutSeconds: 1, retries: 0});
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) =>
      Object.entries(layout.min ?? {}).forEach(([id, min]) => {
        expect(typeof min).withContext(`${modelType}.${id}`).toBe('number');
        // A floor on a field nobody can see refuses a save with nothing to correct.
        expect((layout.hidden ?? []).includes(id)).withContext(`${modelType}.${id}`).toBe(false);
        expect(Object.keys(layout.visibleWhen ?? {}).includes(id))
          .withContext(`${modelType}.${id}`).toBe(false);
      }));
  });

  it('never hints a field it also hides', () => {
    // A hidden field has no control and no icon to hang a tooltip on, so a hint on one is text nobody
    // can reach -- and a sign that one of the two lines is stale.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      Object.keys(layout.hints ?? {}).forEach(id =>
        expect(hidden.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  const controller = GATEWAY_FORM_LAYOUTS['MESH_CONTROLLER.DS'];
  const controllerPoint = GATEWAY_FORM_LAYOUTS['MESH_CONTROLLER.PL'];

  it('takes the mesh controller\'s Add-point button away, because the gateway makes the point', () => {
    // `CreateMeshControllerVO.createDataSource` saves the source and then `createDataPoints` creates
    // one point per `MeshControllerAttributes` constant -- there is one. An Add button could only
    // offer a duplicate of it.
    expect(controller.provisionedPoints).toBe(true);
  });

  it('does not let the mesh address be retyped, because the mesh files records under it', () => {
    // `deleteRelationalData` keys `deviceCache.addRemoveDevices` and
    // `MeshNodeInfoService.deleteByAddress` on `vo.getAddress()`, so an edited address orphans the
    // mesh-node row under the old one. And the gateway does not mean the type to be created by hand
    // at all: `MeshControllerDataSourceDefinition.isEnabled()` returns false, and a source created
    // through REST gets no points, because `createDataPoints` runs only from `CreateMeshControllerVO`.
    expect(controller.readonly).toEqual(['address']);
    // Not *also* required with a floor: a disabled control is left out of Angular's validation, so
    // the pair would read as a rule and enforce nothing. The gateway's rule (0 and -1 refused, and
    // the model's own initialiser is -1) is written in the comment instead.
    expect(controller.required).toBeUndefined();
    expect(controller.min).toBeUndefined();
  });

  it('writes the mesh controller\'s one attribute out rather than trusting the gateway to check', () => {
    // `MeshPointLocatorVO` has one `public static ATTRIBUTE_CODES` and 34 subclasses reassign it from
    // their own static initialiser, so the last class to load decides what every mesh locator
    // validates against. Measured on 5.1.3: a MESH_CONTROLLER point saved 201 with
    // `attributeId: "BATTERY"` -- which this type's own table does not declare, and which the probe
    // narrowed the live table to `MeshExtenderAttributes` for -- and read back as "BATTERY".
    // D90. A one-option list is the only refusal available on this side.
    expect(controllerPoint.options.attributeId.map(item => item.value)).toEqual(['HEARTBEAT']);
    expect(controllerPoint.defaults.attributeId).toBe('HEARTBEAT');
    // HEARTBEAT's conversion builds a `BinaryValue`, and the provisioner stores the attribute's own
    // BINARY. Nothing on the gateway refuses another type.
    expect(controllerPoint.options.dataType.map(item => item.value)).toEqual(['BINARY']);
    expect(controllerPoint.defaults.dataType).toBe('BINARY');
  });

  it('leaves a provisioned mesh controller point nothing to edit', () => {
    // What `provisionedPoints` already claims of every locator field on a provisioned point, and what
    // `readonly`'s own description gives as its example -- "the attribute a point reads". The option
    // lists stay: a disabled select still needs its item to render a label.
    expect(controllerPoint.readonly).toEqual(['attributeId', 'dataType']);
  });

  it('hides the two mesh controller locator fields toVO never copies', () => {
    // `MeshControllerPointLocatorModel.toVO` builds a fresh VO and copies `attributeId` and
    // `dataType` alone. Measured: `settable: true` saves 201 and reads back false. Unlike
    // MESH_SWITCH and MESH_UART this locator is not read-only by construction --
    // `MeshPointLocatorVO.isSettable()` answers the stored field -- it is never given one. D91.
    expect(controllerPoint.hidden)
      .toEqual(['settable', 'relinquishable', 'configurationDescription']);
  });

  const pingPoint = GATEWAY_FORM_LAYOUTS['PING.PL'];

  it('marks the ping data source worked through without overriding anything', () => {
    // `PingDataSourceVO` adds no field to `PollingDataSourceVO`, so the type is the poll period plus
    // the two the mapper already puts under Advanced -- field-identical to `VIRTUAL.DS`, and the same
    // empty entry. An entry that is absent instead would keep the old renderer.
    expect(GATEWAY_FORM_LAYOUTS['PING.DS']).toEqual({});
  });

  it('fixes a ping point to binary, and defaults it so the disabled box is not empty', () => {
    // `getDataTypeId()` returns `DataTypes.BINARY` and `PingPointLocatorModel.toVO` copies
    // `ipAddress` and `timeout` alone, so a submitted type is not read at all -- measured, a point
    // sent `NUMERIC` saves 201 and reads back `BINARY`, and so does one sent no `dataType` at all.
    // The default is cosmetic: it gives the disabled select an item to show. It is not what gets a
    // new point past the mapper's blanket `required`, which is inert on a disabled control either
    // way.
    expect(pingPoint.readonly).toEqual(['dataType']);
    expect(pingPoint.options.dataType.map(item => item.value)).toEqual(['BINARY']);
    expect(pingPoint.defaults.dataType).toBe('BINARY');
  });

  it('never gives a field a floor above zero without also requiring it', () => {
    // Angular's `minValidator` returns null -- valid -- when the control is empty, so a floor alone
    // passes an untouched box. The null then lands on the REST model's primitive `int` as 0, which
    // is exactly the value a floor above zero exists to refuse, and the operator meets the gateway's
    // 422 instead of the form's own message. A floor of 0 does not need the pair: a null becoming 0
    // is the floor being met.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const required = new Set(layout.required ?? []);
      Object.entries(layout.min ?? {}).filter(([, floor]) => floor > 0).forEach(([id]) =>
        expect(required.has(id)).withContext(`${modelType}.${id}`).toBe(true));
    });
  });

  it('carries the gateway\'s own two ping rules rather than a stricter pair', () => {
    // `PingDataSourceDefinition.validate` refuses an empty `ipAddress` and a `timeout` of `<= 0`.
    // Measured: 422 "Required value" on the address, 422 "Cannot be 0" for -5, 0 and an omitted
    // timeout alike. `min: 1` is the client-side half of the second, and the default keeps a new
    // point from opening on the value the gateway refuses.
    expect(pingPoint.required).toEqual(['ipAddress', 'timeout']);
    expect(pingPoint.min.timeout).toBe(1);
    expect(pingPoint.defaults.timeout).toBe(1000);
  });

  it('hides the ping locator fields that are fixed by construction', () => {
    // `isSettable()` is false and `PingDataSourceRT.setPointValue` is an empty method, so a ping
    // point can never be written; `toVO` reads neither field. Measured: both submitted true, the
    // point reads back `settable: false` and `relinquishable: null`.
    expect(pingPoint.hidden).toEqual(['settable', 'relinquishable', 'configurationDescription']);
  });

  const poe = GATEWAY_FORM_LAYOUTS['POE_LIGHTING.DS'];
  const poePoint = GATEWAY_FORM_LAYOUTS['POE_LIGHTING.PL'];

  it('writes the PoE point types out, because the schema publishes a bare string', () => {
    // `pointType` is `{"type": "string"}` with no enum, and `PoeLightingPointLocatorModel.toVO`
    // calls `PointType.valueOf(pointType)` on it unguarded. Measured: an omitted value is a **500**,
    // an unknown name a 400, and the enum's own display string "Channel Level" a 400 -- the wire
    // takes the constant name. The list is the only thing between an operator and that 500.
    expect(poePoint.options.pointType.map(item => item.value))
      .toEqual(['CHANNEL_LEVEL', 'POWER_ON_SETTING']);
    expect(poePoint.defaults.pointType).toBe('CHANNEL_LEVEL');
  });

  it('hides the PoE data type, because it is derived from the field beside it', () => {
    // `getDataTypeId()` is a switch on `pointType`: CHANNEL_LEVEL is NUMERIC, POWER_ON_SETTING is
    // BINARY. A disabled box would show one while the operator picks the other, and the layout
    // language cannot express a derivation. `build` filters hidden properties out before
    // `addControl`, so the mapper's blanket `required` on a locator data type has no control to
    // fail. Measured: a point submitted ALPHANUMERIC stores NUMERIC, and one omitting the field
    // stores the type its point type implies.
    expect(poePoint.hidden).toEqual(['dataType', 'relinquishable', 'configurationDescription']);
    expect(poePoint.options.dataType).toBeUndefined();
    expect(poePoint.defaults.dataType).toBeUndefined();
  });

  it('keeps settable on a PoE point, and defaults it on', () => {
    // Unlike `PING.PL` this locator's `isSettable()` answers a stored field and `toVO` copies it --
    // measured, `settable: true` reads back true. `PoeLightingService.createDataPoints` passes true
    // for both point types on every discovered channel.
    expect(poePoint.hidden).not.toContain('settable');
    expect(poePoint.defaults.settable).toBe(true);
  });

  it('hides the PoE connection timeout, which nothing on the gateway reads', () => {
    // Declared on the VO with a default of 10, serialised, mapped both ways, and never referenced
    // again in the tree -- unlike `retries`, which the runtime checks on every attempt.
    expect(poe.hidden).toEqual(['connectionTimeoutSeconds']);
    expect(poe.defaults.retries).toBe(2);
    // Defaulted although hidden, which is not redundant: the field has the same three-way split as
    // `retries` -- VO 10, REST model 0, discovery 10 -- so an add that dropped the key would store
    // 0, and the gateway's own editor shows the field, where every other source reads 10.
    expect(poe.defaults.connectionTimeoutSeconds).toBe(10);
  });

  it('requires the two PoE fields the gateway does not validate at all', () => {
    // Both `PoeLightingDataSourceDefinition.validate` overloads are empty methods, so this is
    // stricter than the gateway -- allowed here because an empty address or token is not a
    // configuration, it is a source that can never reach anything. `createHeaders` does
    // `setBearerAuth(token)` and every call is `baseUrl + path`.
    expect(poe.required).toEqual(['ipAddress', 'token']);
  });

  const scripting = GATEWAY_FORM_LAYOUTS['SCRIPTING.DS'];
  const scriptPoint = GATEWAY_FORM_LAYOUTS['SCRIPTING.PL'];

  it('seeds every sendEmpty field, because the add path only sees what the model carries', () => {
    // `pick` copies a key from the model only when the model has it, and on an add the model is
    // `gatewayFormDefaults(type)`. A `sendEmpty` id that is not also a `defaults` key never reaches
    // `keep()` on an add, so the branch does not fire and the gateway gets the absent key it
    // crashes on. The two are a pair; nothing else enforces it.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      (layout.sendEmpty ?? []).forEach(id =>
        expect(Object.prototype.hasOwnProperty.call(layout.defaults ?? {}, id))
          .withContext(`${modelType}.${id}`).toBe(true));
    });
  });

  it('sends an empty script rather than requiring one, because blank is legal and absent is a 500', () => {
    // `commonValidation` hands `service.compile(vo.getScript(), false)` whatever arrived. Measured:
    // no `script` key is a 500 in the Nashorn source constructor, `script: ""` is a 201. So the
    // field belongs in `sendEmpty`, not in `required` -- requiring it would refuse a stub source the
    // gateway stores happily. `MetaPointLocatorVO.validate` answers the same omission 422
    // "Required value", which is the check scripting is missing. D104.
    expect(scripting.sendEmpty).toContain('script');
    expect(scripting.required).toBeUndefined();
    expect(scripting.defaults.script).toBe('');
  });

  it('sends an empty scriptPermissions, because an absent one is a 500', () => {
    // `ScriptDataSourceModel.toVO` calls `new ScriptPermissions(scriptPermissions)` and that
    // constructor does `groups.split(",")` with no null check. Measured: an add omitting the key is
    // a 500, and so is a PATCH of a source's own unmodified body, because the read hands back
    // `scriptPermissions: null`. The sibling `MetaPointLocatorModel` guards it, which is why
    // `META.PL` hides the same field and needs nothing. D101.
    expect(scripting.sendEmpty).toContain('scriptPermissions');
    expect(scripting.defaults.scriptPermissions).toBe('');
  });

  it('may name a hidden field in sendEmpty, because the value is carried not dropped', () => {
    // A spec used to forbid this pair on the reasoning that "a hidden field has no control to be
    // empty". The plumbing says otherwise: `pick` copies every schema property present on the
    // model, and `GatewayFormComponent` merges its rendered controls *over* the value it was
    // written rather than emitting them alone, so a hidden key reaches `keep()` on both paths.
    // Hiding `scriptPermissions` is the security decision `META.PL` documents; sending `""` is what
    // keeps the gateway from crashing on it.
    expect(scripting.hidden).toEqual(['scriptPermissions']);
    expect(scripting.sendEmpty).toContain('scriptPermissions');
  });

  it('offers the three update events the scripting validator accepts, not the five published', () => {
    // `commonValidation` switches on `updateEvent` and answers "Invalid value" for NONE and CRON --
    // measured. The REST model leaves the field null, which is refused "Required value", so the
    // default carries the VO's own initialiser.
    expect(scripting.options.updateEvent.map(item => item.value))
      .toEqual(['UPDATE', 'CHANGE', 'LOGGED']);
    expect(scripting.defaults.updateEvent).toBe('UPDATE');
  });

  it('seeds the scripting log defaults the REST model loses', () => {
    // `new ScriptDataSourceVO()` sets logLevel NONE, logSize 1.0 and logCount 5; the model declares
    // the last two as primitives, so a POST omitting them reads back 0.0 and 0 -- a log file rotated
    // at zero megabytes, none of them kept.
    expect(scripting.defaults.logLevel).toBe('NONE');
    expect(scripting.defaults.logSize).toBe(1);
    expect(scripting.defaults.logCount).toBe(5);
  });

  it('requires a scripting point\'s variable name and leaves its two other rules to the hint', () => {
    // `ScriptingDataSourceDefinition.validate` refuses a blank name "Required value", a non-identifier
    // "Invalid value", and one already used by another point or a context variable
    // "Duplicate variable name". Only the first is expressible in a layout.
    expect(scriptPoint.required).toEqual(['varName']);
    expect(scriptPoint.hints.varName).toContain('JavaScript name');
    // Unlike PoE the data type is a real choice here: `toVO` maps it and an omitted one is refused.
    expect(scriptPoint.options.dataType.map(item => item.value)).not.toContain('IMAGE');
    expect(scriptPoint.defaults.dataType).toBe('NUMERIC');
  });

  it('never names a field in both required and readonly', () => {
    // `readonly` disables the control, and Angular leaves a disabled control out of validation
    // entirely -- so the pair reads as a rule and enforces nothing. Same family as the `advanced` and
    // gated cases, and the reason `INTERNAL.PL` requires `monitorId` and only disables `dataType`.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const readonly = new Set(layout.readonly ?? []);
      (layout.required ?? []).forEach(id =>
        expect(readonly.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('never names a field in both required and hidden', () => {
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      (layout.required ?? []).forEach(id =>
        expect(hidden.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('leaves the private key a password field, so a blank one cannot erase it', () => {
    // `privateKey` is `writeOnly`, which is what types it as a password, which is what makes `keep()`
    // drop an empty one instead of sending it. A textarea would read better for a PEM block and would
    // turn every save of an unchanged key into an erasure.
    expect(mqtt.types.privateKey).toBeUndefined();
    expect(mqtt.types.x509CaCrt).toBe(FormPropertyType.textarea);
    expect(mqtt.types.topicFilters).toBe(FormPropertyType.textarea);
  });

  it('gates the client certificate on the switch and the CA on nothing', () => {
    // `validateURI` requires a CA for any `ssl://` broker whatever `useCertificate` says, so gating
    // the CA would hide the field that refusal names.
    expect(Object.keys(mqtt.visibleWhen)).toEqual(['x509ClientCrt', 'privateKey']);
    expect(mqtt.visibleWhen.privateKey).toEqual({by: 'useCertificate', values: [true]});
  });

  it('hides the two MQTT locator fields the gateway derives or never reads', () => {
    // `isSettable()` answers `publishTopic != null && length > 0`, and a valid point always has one.
    expect(mqttPoint.hidden).toEqual(['settable', 'relinquishable', 'configurationDescription']);
  });

  it('gates no field on a field that is itself gated', () => {
    // A gate hides a row and keeps its control, so a rule reading a gated field reads a value the
    // operator can no longer see. Gating `authPassphrase` on `authProtocol` is the precise
    // condition and was the first attempt: choosing v3 and MD5 and going back to v2c left the
    // passphrase on a v2c form, with the protocol that summoned it hidden. Measured on screen.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const gated = new Set(Object.keys(layout.visibleWhen ?? {}));
      Object.values(layout.visibleWhen ?? {}).forEach(rule =>
        expect(gated.has(rule.by)).withContext(`${modelType} by ${rule.by}`).toBe(false));
    });
  });

  it('spells the v3 protocols the way an agent spells them', () => {
    // Relabelled, not narrowed: the mapper already offers exactly these values from the published
    // enums, but its humaniser writes "Md5" and "Aes256".
    expect(snmp.options.authProtocol.map(item => item.label)).toEqual(['None', 'MD5', 'SHA']);
    expect(snmp.options.privProtocol.map(item => item.value))
      .toEqual(['NONE', 'DES', 'AES128', 'AES192', 'AES256']);
    expect(snmp.options.privProtocol.map(item => item.label))
      .toEqual(['None', 'DES', 'AES128', 'AES192', 'AES256']);
  });

  it('sends both SNMP protocols whatever the version, because a null is a 500', () => {
    // `ReverseEnumMap.get` calls `Objects.requireNonNull`, and the model that builds the response
    // from the saved VO goes through it -- so an absent protocol is a 500 on a v2c source where
    // neither means anything, with the row already written. Measured on 5.1.1.
    expect(snmp.defaults.authProtocol).toBe('NONE');
    expect(snmp.defaults.privProtocol).toBe('NONE');
    // The `int` family: what `SnmpDataSourceVO` starts on, where the model declares a bare int.
    expect(snmp.defaults.port).toBe(161);
    expect(snmp.defaults.trapPort).toBe(162);
    expect(snmp.defaults.timeout).toBe(1000);
    expect(snmp.defaults.retries).toBe(2);
    // No VO default to take: `snmpVersion` is a bare int there too, so its 0 is Java's rather than
    // a decision, and v2c has the same field set as v1.
    expect(snmp.defaults.snmpVersion).toBe('v2c');
  });

  it('offers the ten set types SET_TYPE_CODES declares, in its own order', () => {
    expect(snmpPoint.options.setType.map(item => item.value)).toEqual(
      ['NONE', 'INTEGER_32', 'OCTET_STRING', 'OID', 'IP_ADDRESS', 'COUNTER_32', 'GAUGE_32',
        'TIME_TICKS', 'OPAQUE', 'COUNTER_64']);
    // Id 0 is not a type but the absence of one -- `isSettable()` is `setType != 0` -- so it reads
    // as what it does rather than as "None".
    expect(snmpPoint.options.setType[0].label).toBe('Not settable');
  });

  it('hides settable on an SNMP point, because the gateway derives it', () => {
    // `SnmpPointLocatorVO.isSettable()` returns `setType != 0`, and
    // `SnmpPointLocatorModel.toVO` builds a fresh VO from seven fields, none of them that one.
    expect(snmpPoint.hidden).toContain('settable');
    expect(snmpPoint.hidden).toContain('relinquishable');
    // So nothing may gate on it either -- the cross-cutting spec below would catch that, but the
    // reason it is hidden is this one rather than the usual "the gateway reports it".
    expect(snmpPoint.visibleWhen.multiplicand).toEqual({by: 'dataType', values: ['NUMERIC']});
  });

  it('defaults the two SNMP point fields whose absence is silent', () => {
    // `multiplicand` is 1.0D on the VO and a bare double on the model: absent stores 0 and scales
    // every reading to nothing. `setType` is worse -- `ExportCodes.getId(null)` returns -1, which
    // is not zero, so the point reports itself settable with a set type nothing answers to. Both
    // were 201 Created with no warning.
    expect(snmpPoint.defaults.multiplicand).toBe(1);
    expect(snmpPoint.defaults.setType).toBe('NONE');
    expect(snmpPoint.defaults.binary0Value).toBe('0');
    expect(snmpPoint.defaults.dataType).toBe('NUMERIC');
  });

  it('gates no field on one that is hidden', () => {
    // A gate reads its control's value, and `build` only creates controls for shown fields. A rule
    // naming a hidden one reads undefined and hides its field for good.
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      Object.values(layout.visibleWhen ?? {}).forEach(rule =>
        expect(hidden.has(rule.by)).withContext(`${modelType} by ${rule.by}`).toBe(false));
      Object.values(layout.gatedOptions ?? {}).forEach(gate =>
        expect(hidden.has(gate.by)).withContext(`${modelType} by ${gate.by}`).toBe(false));
    });
  });

  it('names no field in both hidden and advanced', () => {
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const hidden = new Set(layout.hidden ?? []);
      (layout.advanced ?? []).forEach(id =>
        expect(hidden.has(id)).withContext(`${modelType}.${id}`).toBe(false));
    });
  });

  it('never names the same field in two explicit rows', () => {
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      const ids = (layout.rows ?? []).flat();
      expect(new Set(ids).size).withContext(modelType).toBe(ids.length);
    });
  });

  const retriever = GATEWAY_FORM_LAYOUTS['HTTP_JSON_RETRIEVER.DS'];
  const retrieverPoint = GATEWAY_FORM_LAYOUTS['HTTP_JSON_RETRIEVER.PL'];

  it('refuses an empty timeout and retry count, because the gateway blames another field', () => {
    // Measured: `timeoutSeconds: 0` or `null` comes back 422 *"Must be greater than zero"* against
    // `updatePeriods`, which this form does not have -- the definition files the message under the
    // wrong property name. Seeding the model's own 30 and 2 and refusing an empty box is the only way
    // the operator ever sees which field is wrong.
    expect(retriever.required).toEqual(['url', 'timeoutSeconds', 'retries']);
    expect(retriever.defaults.timeoutSeconds).toBe(30);
    expect(retriever.defaults.retries).toBe(2);
    // And neither may be advanced, or the refusal happens behind a closed toggle.
    expect(retriever.advanced).toEqual(['setPointUrl']);
  });

  it('says nothing about the bearer token\'s type, and gates it on the switch that sends it', () => {
    // It was a textarea on purpose: the schema did not mark it `writeOnly`, so the read carried the
    // token, and typing it `password` would have masked a value `keep()` then dropped when emptied --
    // making a stored token impossible to remove. D79 marked it `writeOnly`, so the mapper types it
    // `password` on its own and the empty-drop is exactly right: the read no longer carries it and
    // blank means unchanged, which is what `SecretFields.merge` does for all four credentials on this
    // gateway. Measured: the token is absent from `GET /v2/data-source/{xid}`. A layout type here
    // would now be overriding the schema rather than compensating for it.
    expect(retriever.types).toBeUndefined();
    expect(retriever.visibleWhen.bearerToken).toEqual({by: 'bearerAuth', values: [true]});
  });

  it('does not require a retriever point\'s pointer, because a write-only point has none', () => {
    // It was required while `settable` was hidden, and that matched the gateway. Unhiding `settable`
    // opened the case it gets wrong: measured 201 for `settable: true` with a set point key and no
    // `valuePointer` at all. A layout cannot say "required unless that switch is on", and a refusal
    // the gateway does not make would leave a legitimate point unsaveable.
    expect(retrieverPoint.required).toBeUndefined();
    expect(retrieverPoint.hints.valuePointer).toContain('never read');
  });

  it('seeds a retriever point\'s data type', () => {
    // The data type used to store -1 and read back null; since D65 it is refused outright, which is
    // why the mapper marks it required for every locator and the seed here is what stops that refusal
    // being the operator's first contact with the rule.
    expect(retrieverPoint.defaults).toEqual({dataType: 'NUMERIC'});
  });

  it('hides only the retriever locator field no code reads, now that two are read', () => {
    // `relinquishable` is still discarded in `toVO`. The other two were hidden for the same reason and
    // are not any more: D76 made `isSettable()` answer the stored field, which is the only entrance to
    // a fully-written set-point path, and D77 made `ignoreIfMissing` suppress the parse event it was
    // always meant to suppress. Measured: `settable: true` without a `setPointName` is a 422 naming
    // `setPointName`, which is what the hint and the gate are for.
    expect(retrieverPoint.hidden).toEqual(['relinquishable', 'configurationDescription']);
    expect(retrieverPoint.visibleWhen.setPointName).toEqual({by: 'settable', values: [true]});
    expect(retrieverPoint.hints.settable).toContain('Set point URL');
    expect(retrieverPoint.advanced).toBeUndefined();
  });

  const internal = GATEWAY_FORM_LAYOUTS['INTERNAL.DS'];
  const internalPoint = GATEWAY_FORM_LAYOUTS['INTERNAL.PL'];

  it('leaves the internal source its regex and adds no point button', () => {
    // `createPointsPattern` is the whole data source, and `provisionedPoints` would be wrong: the
    // gateway creates points only when a pattern is set, and the live source on the bench has none, so
    // removing the add button would leave no way to add one.
    expect(Object.keys(internal)).toEqual(['hints']);
    expect(internal.hints.createPointsPattern).toContain('id');
    expect(internal.provisionedPoints).toBeUndefined();
  });

  it('offers the internal point\'s data type, now that the gateway keeps what it is sent', () => {
    // It was {@link readonly} while `InternalPointLocatorModel.toVO` built a fresh VO and set
    // `monitorId` alone -- a submitted type never reached storage, and a PUT on the gateway's own
    // ALPHANUMERIC point reset it. D84 made the model copy it like the other 53. Measured:
    // ALPHANUMERIC reads back ALPHANUMERIC.
    expect(internalPoint.readonly).toBeUndefined();
    expect(internalPoint.defaults.dataType).toBe('NUMERIC');
  });

  it('requires a monitor id and seeds one that exists', () => {
    // An absent id is a 422 (`getMonitor(null)` throws inside the validator's try) but a wrong one is
    // accepted 201 and reads nothing for ever, so the seed is a real monitor -- the one the VO itself
    // starts on, measured resolving to "Waiting High Priority Threads".
    expect(internalPoint.required).toEqual(['monitorId']);
    expect(internalPoint.defaults.monitorId)
      .toBe('com.inferrix.stack.rt.maint.WorkItemMonitor.highPriorityWaiting');
  });

  it('seeds a poll period on a polling type and on no other, from the schema', () => {
    // `timePeriod` and its own `timePeriodType` are the only two names that appear in any `required`
    // array in the whole schema document, 28 sites and 1 -- and `timePeriod` is a delegated fieldset,
    // so a source saved without opening it posts `{}` and is refused, while one with a unit and no
    // count is accepted with a zero period, because nothing validates the count on any type.
    const polling = [{id: 'url'}, {id: 'timePeriod'}] as any;
    expect(gatewayFormDefaults('HTTP_JSON_RETRIEVER.DS', polling).timePeriod)
      .toEqual({timePeriod: 5, timePeriodType: 'MINUTES'});
    // A fresh object each time, like the rest of the defaults.
    expect(gatewayFormDefaults('HTTP_JSON_RETRIEVER.DS', polling).timePeriod)
      .not.toBe(gatewayFormDefaults('HTTP_JSON_RETRIEVER.DS', polling).timePeriod);
    // Not on a type whose schema does not declare one, whatever the layout says.
    expect(gatewayFormDefaults('HTTP_RECEIVER.DS', [{id: 'ipWhiteList'}] as any).timePeriod)
      .toBeUndefined();
    expect(gatewayFormDefaults('HTTP_JSON_RETRIEVER.DS').timePeriod).toBeUndefined();
    // And a layout that names one itself is left alone.
    expect(gatewayFormDefaults('MODBUS_IP.DS', polling).timePeriod)
      .toEqual({timePeriod: 5, timePeriodType: 'MINUTES'});
  });

  const sysAttrPoint = GATEWAY_FORM_LAYOUTS['SYSTEM_ATTRIBUTES.PL'];

  it('marks the system attributes source worked through', () => {
    // An empty entry says the type was read and keeps the form on this renderer; an absent one would
    // fall back to the old one. It cannot say more than that: `VIRTUAL.DS` is `{}` too and *is* a
    // polling source. What is true of this one -- `SystemAttributesDataSourceVO` extends
    // `DataSourceVO` directly, so it has no poll period at all, measured on the created source -- is
    // a fact about the schema, not about the layout, and no assertion here can reach it.
    expect(GATEWAY_FORM_LAYOUTS['SYSTEM_ATTRIBUTES.DS']).toEqual({});
  });

  it('requires an attribute type, because an unknown one is not refused but skipped', () => {
    // `attributeType` is a bare string over an `ExportCodes` table and `ExportCodes.getId` answers
    // **-1** for a name it does not carry, which matches no branch of either the validator's
    // `if/else if` or `toVO`'s `switch`. Measured: a point sent no `attributeType` and no
    // `startValue` saves 201 and reads back both null -- and then behaves as a boolean starting at
    // zero, because `getAttribute()` falls through to `default -> booleanAttribute`. D105.
    expect(sysAttrPoint.required).toEqual(['attributeType', 'startValue']);
    expect(sysAttrPoint.defaults.attributeType).toBe('BOOLEAN_ATTRIBUTE');
    expect(sysAttrPoint.defaults.dataType).toBe('BINARY');
  });

  it('offers only the attribute types that match the data type, as the gateway itself does', () => {
    // The attribute type decides the value class the runtime answers -- `BooleanAttributeRT` and
    // `TimerAttributeRT` a `BinaryValue`, `AnalogAttributeRT` a `NumericValue`,
    // `AlphanumericAttributeRT` an `AlphanumericValue` -- so the two fields have to agree or every
    // write stores the wrong class. `AttributeTypeVO.getAttributeTypes(dataTypeId)` and the webapp's
    // `dataTypeChange` carry exactly these three lists; the REST path carries none of it, measured
    // 201 for NUMERIC with a `BOOLEAN_ATTRIBUTE`. D107.
    expect(sysAttrPoint.gatedOptions.attributeType.by).toBe('dataType');
    expect(Object.entries(sysAttrPoint.gatedOptions.attributeType.table)
      .map(([dataType, items]) => [dataType, items.map(item => item.value)]))
      .toEqual([
        ['BINARY', ['BOOLEAN_ATTRIBUTE', 'TIMER_ATTRIBUTE']],
        ['NUMERIC', ['ANALOG_ATTRIBUTE']],
        ['ALPHANUMERIC', ['ALPHANUMERIC_ATTRIBUTE']]
      ]);
    // No fallback: the field holds an enum constant on every data type the locator offers, so a
    // select with nothing in it is the right answer to a gate value that should not arise.
    expect(sysAttrPoint.gatedOptions.attributeType.unlisted).toBeUndefined();
  });

  it('drops the data type no attribute type can serve, keeping the other three', () => {
    // There is no `MultistateAttributeRT`, so a MULTISTATE point is the wrong class from its first
    // sample. `createRuntime` does parse a multistate start value, but it hands it to
    // `SystemAttributesPointLocatorRT.currentValue`, which nothing reads -- the value the point
    // starts on comes from `addDataPointImpl` via the attribute runtime's `getStartValue`. The
    // webapp reaches the same place from the other end, falling through to an empty attribute-type
    // list. Measured: MULTISTATE is a 201 with any attribute type or none.
    expect(sysAttrPoint.options.dataType.map(item => item.value))
      .toEqual(['BINARY', 'NUMERIC', 'ALPHANUMERIC']);
  });

  it('gives the start value the same two words as a virtual point, from one table', () => {
    // Both parse it out of free text for every data type but binary. Shared rather than copied: two
    // tables that must agree are a table that will not.
    expect(sysAttrPoint.gatedOptions.startValue)
      .toBe(GATEWAY_FORM_LAYOUTS['VIRTUAL.PL'].gatedOptions.startValue);
    expect(sysAttrPoint.gatedOptions.startValue.by).toBe('dataType');
    expect(sysAttrPoint.gatedOptions.startValue.table.BINARY.map(item => item.value))
      .toEqual(['true', 'false']);
  });

  it('shows the timer count on the timer type alone, and seeds it rather than requiring it', () => {
    // `startTimer` multiplies it by 1000 and `validate` refuses `<= 0` -- measured, 422 *"Must be
    // greater than zero"* on `timerAttribute.timerValue` for a zero, and 201 storing 30 for a valid
    // one. Neither rule may carry it here, for two different reasons. `min` because the field is a
    // primitive `int` that `fromVO` fills only in the TIMER branch, so every non-timer point reads
    // back 0 -- measured -- and a floor would fail the form on open, on a hidden control, with
    // nothing to correct. `required` because it would be inert: the control is never empty, and
    // `Validators.required` treats 0 as a value. The seed is the floor itself.
    expect(sysAttrPoint.visibleWhen.timerValue)
      .toEqual({by: 'attributeType', values: ['TIMER_ATTRIBUTE']});
    expect(sysAttrPoint.required).not.toContain('timerValue');
    expect(sysAttrPoint.min).toBeUndefined();
    expect(sysAttrPoint.defaults.timerValue).toBe(1);
  });

  it('keeps a system attribute writable, and hides the two fields the gateway fixes', () => {
    // Unlike every other locator worked through so far, `isSettable()` answers the stored field and
    // `toVO` copies it -- measured, `settable: true` is honoured -- so hiding it would turn every
    // point into a constant. `relinquishable` is never read and reads back null, and
    // `configurationDescription` is the attribute type's own name, measured "Boolean Attribute".
    expect(sysAttrPoint.hidden).toEqual(['relinquishable', 'configurationDescription']);
    expect(sysAttrPoint.hints.settable).toContain('constant');
  });

  it('lets a gated field be required, and says what that costs', () => {
    // `SYSTEM_ATTRIBUTES.PL` is the only layout that names a field in both, and it names two. The
    // pair has a consequence worth pinning rather than forbidding: `clearIllegalGatedValues` runs on
    // every `valueChanges` and nulls a gated control whose stored value is not in the list its gate
    // selects, so opening a row the gateway accepted in a pairing we refuse (D107) and touching any
    // control blanks the field. `required` then blocks the save instead of writing the bad pairing
    // back, which is the outcome we want. Opening and saving untouched is unaffected: the form
    // patches with `{emitEvent: false}`.
    //
    // This is not a licence to combine them freely. A gated field that is required must be one
    // where clearing is the right answer to an illegal stored value -- never one where the value is
    // merely unrecognised, which is why `startValue` has an `unlisted` fallback and is only gated
    // where the list is exhaustive.
    const gatedAndRequired = Object.entries(GATEWAY_FORM_LAYOUTS)
      .filter(([, layout]) => (layout.required ?? [])
        .some(id => Object.keys(layout.gatedOptions ?? {}).includes(id)))
      .map(([modelType]) => modelType);
    expect(gatedAndRequired).toEqual(['SYSTEM_ATTRIBUTES.PL']);
    // And a gated field that is required is never also hidden by a `visibleWhen` gate, which would
    // make the form unsubmittable with nothing on screen to correct.
    gatedAndRequired.forEach(modelType => (GATEWAY_FORM_LAYOUTS[modelType].required ?? [])
      .forEach(id => expect(Object.keys(GATEWAY_FORM_LAYOUTS[modelType].visibleWhen ?? {}))
        .withContext(`${modelType}.${id}`).not.toContain(id)));
  });

  const thermostat = GATEWAY_FORM_LAYOUTS['THERMOSTAT.DS'];
  const thermostatPoint = GATEWAY_FORM_LAYOUTS['THERMOSTAT.PL'];

  it('gives the thermostat source the mesh-device shape row 13 established', () => {
    // `ThermostatDataSourceModel` adds `address`, `anchorNode` and `location` over
    // `AbstractDataSourceModel`'s eleven, which is field-identical to `MESH_CONTROLLER.DS` and to
    // twenty-four other provisioned types.
    // The address is the mesh's, not ours -- `validate` refuses -1 and 0, measured 422 for both.
    expect(thermostat.provisionedPoints).toBe(true);
    expect(thermostat.readonly).toEqual(['address']);
    expect(thermostat.rows).toEqual([['address', 'location']]);
  });

  it('carries the attribute names the wire takes, not the ones the enum constants are called', () => {
    // `ThermostatPointLocatorVO`'s static block adds each constant's `attributeName` to the code
    // table, and for one constant the two differ: `ENERGY_SAVING` carries `ENERGY_SAVING_MODE`. The
    // gateway's own dropdown endpoint publishes the constant name -- `.map(Enum::name)` -- so a
    // client that trusted it would post a value the same gateway refuses. D113.
    const values = thermostatPoint.options.attributeId.map(item => item.value);
    expect(values).toEqual(['HEARTBEAT', 'STATUS', 'LOCK', 'RHV_STATUS', 'FAN_SPEED', 'TEMPERATURE',
      'ENERGY_SAVING_MODE', 'AUTO_MANUAL', 'SETPOINT_TEMPERATURE']);
    expect(values).not.toContain('ENERGY_SAVING');
  });

  it('shows a thermostat point\'s settable flag, disabled, instead of hiding it', () => {
    // The first locator in this sequence where the flag is worth showing: `isSettable()` answers the
    // stored field and the provisioner fills it from the enum, so seven of the nine attributes
    // really are writable -- everything but the heartbeat and the temperature. It is disabled because `toVO` builds a fresh VO and copies `attributeId` and
    // `dataType` alone -- measured, a submitted `true` reads back `false` -- which also means a REST
    // write of a provisioned point erases it. D109.
    expect(thermostatPoint.readonly).toEqual(['attributeId', 'dataType', 'settable']);
    expect(thermostatPoint.hidden).toEqual(['relinquishable', 'configurationDescription']);
    // The hint says how many are writable rather than listing a few as if that were all of them, and
    // it does not claim the thermostat decides -- a Java enum does.
    expect(thermostatPoint.hints.settable).toContain('Seven of the nine');
    expect(thermostatPoint.hints.settable).not.toContain('thermostat decides');
  });

  it('leaves a read-only data type its full list rather than narrowing it', () => {
    // These nine attributes use three of the four, but the field only ever displays what the device
    // reported. Narrowing the list on a field nobody can change cannot prevent a wrong value; it can
    // only blank a right one the gateway does hold.
    // The values, not the array identity: asserting the same object would break the moment either
    // layout spread its list, and would couple this type to `VIRTUAL.PL` for no reason.
    expect(thermostatPoint.options.dataType.map(item => item.value))
      .toEqual(['BINARY', 'MULTISTATE', 'NUMERIC', 'ALPHANUMERIC']);
  });

  it('names no default on a provisioned locator with nine attributes', () => {
    // Row 13 could seed one because a mesh controller has a single attribute and the disabled select
    // needed an item. There is no Add form here either, so a default would not be a convenience --
    // it would be one of nine picked at random for a form nobody opens.
    expect(thermostatPoint.defaults).toBeUndefined();
  });

  // --- the provisioned mesh device family -------------------------------------------------------

  const meshSources = Object.entries(GATEWAY_FORM_LAYOUTS)
    .filter(([modelType]) => modelType.endsWith('.DS'))
    .filter(([, layout]) => JSON.stringify(layout.rows) === JSON.stringify([['address', 'location']]));

  it('gives all 27 provisioned mesh device sources one shape', () => {
    // Measured off the schema document's `families` map, with `allOf` resolved against
    // `components.schemas` and each source paired to its locator through the Java rather than by
    // name: 27 published data source types declare `address`, `anchorNode` and `location` over the
    // common eleven and nothing else. One shape, one factory -- the mesh controller, the thermostat
    // and the current sensor were folded into it rather than left as three more copies of it, even
    // though the last two keep point forms of their own.
    //
    // This is a regression guard over our own table, not a proof that none is missing: a type the
    // gateway publishes and we never added is a type this count never sees. Three were missed on
    // the first pass -- two because their locator is not their own name with `.PL` on the end
    // (`SENSOR_TAG_DOOR_SENSOR.DS` uses `SENSOR_TAG_DOOR.PL`, `SENSOR_TAG_STROKE_COUNT.DS` uses
    // `SENSOR_TAG_STROBE_COUNT.PL`) and one, the current sensor, because its point form differs.
    expect(meshSources.length).toBe(27);
    meshSources.forEach(([modelType, layout]) => {
      expect(layout.provisionedPoints).withContext(modelType).toBe(true);
      expect(layout.readonly).withContext(modelType).toEqual(['address']);
      expect(Object.keys(layout).sort().join(','))
        .withContext(modelType).toBe('hints,provisionedPoints,readonly,rows');
      // The only thing that varies is the noun, and every one of them names something.
      expect(layout.hints.address).withContext(modelType)
        .toMatch(/^The node address the mesh assigned this .+\. It identifies/);
    });
    // A fresh object per type, so a mutation of one cannot reach another. Checked across all of
    // them rather than on the first pair, which two calls of the same factory would pass anyway.
    expect(new Set(meshSources.map(([, layout]) => layout)).size).toBe(meshSources.length);
  });

  // Derived from the layouts themselves rather than by renaming the sources, because two of these
  // locators are not named after their source.
  const meshPoints = Object.entries(GATEWAY_FORM_LAYOUTS)
    .filter(([modelType]) => modelType.endsWith('.PL'))
    .filter(([, layout]) =>
      JSON.stringify(layout.rows) === JSON.stringify([['attributeId', 'dataType']]));

  it('reads each mesh point\'s settable flag from its locator VO, not from a guess', () => {
    // Three dispositions, all read from the Java, and the test is whether the flag can ever hold
    // anything but false -- which takes the VO and the provisioner together, not the VO alone.
    // `hidden` covers both ways of being permanently false: the VO overriding `isSettable()` to a
    // hard false (19 of them), and the VO inheriting the honest accessor while the attribute enum
    // carries no settable flag and no `Create*VO` ever calls `setSettable` (MESH_CONTROLLER,
    // PEOPLE_COUNT_CAMERA, and CURRENT_SENSOR outside this set). `readonly` is where the
    // provisioner calls `setSettable(attribute.isSettable())` over an enum that has `true` entries
    // but `toVO` drops the field on a fresh VO (D109). `editable` is where `toVO` copies it, which
    // is `MODBUS_CONTROLLER` alone, which is also the only type nothing else writes the flag for.
    const shown = (layout): string =>
      (layout.hidden ?? []).includes('settable') ? 'hidden'
        : (layout.readonly ?? []).includes('settable') ? 'readonly' : 'editable';
    const byMode: {[mode: string]: string[]} = {};
    meshPoints.forEach(([modelType, layout]) =>
      (byMode[shown(layout)] = byMode[shown(layout)] ?? []).push(modelType));
    // One editable in the whole family, and it earns it: `ModbusControllerAttributes` carries no
    // settable argument and no provisioner, upgrade or event listener sets the flag for that type,
    // so the form is the only source there is. Everything else that shows the flag shows it locked.
    expect(byMode.editable).toEqual(['MODBUS_CONTROLLER.PL']);
    expect(byMode.readonly.sort()).toEqual(['4DI_2DO_CARD.PL', 'LED_ASSET_TAG.PL',
      'LIGHT_CONTROLLER_V4.PL', 'LIGHT_RELAY_CONTROLLER.PL', 'PEOPLE_COUNTER.PL', 'THERMOSTAT.PL',
      'VAV_CONTROLLER.PL']);
    // Five of those seven are read-only *and* erased on save -- `toVO` drops the field, so a PUT
    // through this dialog overwrites a real value with the VO's default (D109). Two are read-only
    // and round-trip correctly. Same disabled checkbox, two different truths, two different hints.
    const erased = ['4DI_2DO_CARD.PL', 'LED_ASSET_TAG.PL', 'PEOPLE_COUNTER.PL', 'THERMOSTAT.PL',
      'VAV_CONTROLLER.PL'];
    erased.forEach(modelType =>
      expect(GATEWAY_FORM_LAYOUTS[modelType].hints.settable).withContext(modelType)
        .toContain('D109'));
    // The two light controllers are read-only for a different reason from the other four, and the
    // difference matters: their `toVO` copies `settable`, so it round-trips rather than being erased
    // (D109). They are locked because the flag comes from the attribute enum -- what the attribute
    // is, not what the device supports -- and because it picks the BACnet object type the gateway
    // republishes to third-party clients. Row 20 made the opposite call on the mesh node family and
    // had to withdraw it; this is that lesson applied before the same mistake.
    ['LIGHT_CONTROLLER_V4.PL', 'LIGHT_RELAY_CONTROLLER.PL'].forEach(modelType =>
      expect(GATEWAY_FORM_LAYOUTS[modelType].hints.settable).withContext(modelType)
        .toContain('BACnet'));
    expect(byMode.hidden.length).toBe(25);
    expect(byMode.hidden.length + byMode.readonly.length + byMode.editable.length).toBe(33);
    // A flag that is hidden is never also read-only, and one that is shown always carries a hint
    // saying who decides it.
    meshPoints.forEach(([modelType, layout]) => {
      expect((layout.hidden ?? []).includes('settable') && (layout.readonly ?? []).includes('settable'))
        .withContext(modelType).toBe(false);
      if (shown(layout) !== 'hidden') {
        expect(layout.hints.settable).withContext(modelType).toBeTruthy();
      }
    });
  });

  it('locks the attribute and the data type on every provisioned mesh point', () => {
    // The mesh chooses both when it provisions the point, which is what `provisionedPoints` already
    // claims of every locator field on such a point.
    meshPoints.forEach(([modelType, layout]) => {
      expect(layout.readonly.slice(0, 2)).withContext(modelType).toEqual(['attributeId', 'dataType']);
      // Every device in this family reports its own liveness, so a list without `HEARTBEAT` is a
      // list transcribed from the wrong enum. (The current sensor, which measures and does not
      // report one, has its own entry and is not in this set.)
      expect(layout.options.attributeId.map(item => item.value))
        .withContext(modelType).toContain('HEARTBEAT');
      expect(layout.rows).withContext(modelType).toEqual([['attributeId', 'dataType']]);
      // No Add form on any of them, so a default would be invented for a form nobody opens.
      expect(layout.defaults === undefined || modelType === 'MESH_CONTROLLER.PL')
        .withContext(modelType).toBe(true);
    });
  });

  it('carries the five attribute names that are not their constant names', () => {
    // The code table is built from `attributeName`; `GET /v2/export-code/sensors/*` publishes
    // `Enum::name`. Five of the 135 differ, two of the five have spaces in them, and the fifth
    // collides with a name other types use for something else. D115, widened by D121.
    const values = (modelType: string): string[] =>
      GATEWAY_FORM_LAYOUTS[modelType].options.attributeId.map(item => item.value);
    expect(values('THERMOSTAT.PL')).toContain('ENERGY_SAVING_MODE');
    expect(values('THERMOSTAT.PL')).not.toContain('ENERGY_SAVING');
    expect(values('SENSOR_TAG_PIR.PL')).toContain('OCCUPANCY_STATUS');
    expect(values('SENSOR_TAG_PIR.PL')).not.toContain('OCCUPANCY');
    expect(values('SENSOR_TAG_INJECTION_MOULD_COUNT.PL')).toContain('INJECTION MOULD COUNT');
    expect(values('SENSOR_TAG_INJECTION_MOULD_COUNT.PL')).not.toContain('INJECTION_MOULD_COUNT');
    expect(values('SENSOR_TAG_STROBE_COUNT.PL')).toContain('STROKE COUNT');
    expect(values('SENSOR_TAG_STROBE_COUNT.PL')).not.toContain('STROKE_COUNT');
    // And the I/O card's two digital outputs are spelt with a digit zero in the gateway's own enum,
    // while their translation keys call them do1/do2. The value is the zero; the label is the letter.
    const card = GATEWAY_FORM_LAYOUTS['4DI_2DO_CARD.PL'].options.attributeId;
    expect(card.map(item => item.value)).toContain('D01_STATUS');
    expect(card.find(item => item.value === 'D01_STATUS').label).toBe('DO1 status');
    // The relay controller's dim value goes on the wire as `STATUS` (D121), and its DI status is
    // labelled ours rather than the gateway's, because the gateway gives it the lux value's key and
    // two identical entries in one picker is worse than one deviation (D122).
    const relay = GATEWAY_FORM_LAYOUTS['LIGHT_RELAY_CONTROLLER.PL'].options.attributeId;
    expect(relay.map(item => item.value)).toContain('STATUS');
    expect(relay.map(item => item.value)).not.toContain('DIM_VALUE');
    expect(relay.find(item => item.value === 'STATUS').label).toBe('Dim value');
    expect(new Set(relay.map(item => item.label)).size).toBe(relay.length);
    // The V4 list is the seven its locator VO's table actually holds, not the eleven a 2.0 node
    // reports: measured, everything outside the seven is a 422. D123.
    const v4 = GATEWAY_FORM_LAYOUTS['LIGHT_CONTROLLER_V4.PL'].options.attributeId;
    expect(v4.length).toBe(7);
    expect(v4.map(item => item.value)).not.toContain('BURN_HOURS');
  });

  it('gives the four light controller sources the mesh shape plus a poll period', () => {
    ['LIGHT_CONTROLLER_V4', 'LIGHT_DI_CONTROLLER', 'LIGHT_RELAY_CONTROLLER', 'MOKO_BAND']
      .forEach(type => {
        const layout = GATEWAY_FORM_LAYOUTS[`${type}.DS`];
        expect(layout.provisionedPoints).withContext(type).toBe(true);
        expect(layout.readonly).withContext(type).toEqual(['address']);
        // The poll period is pinned under the address row; `quantize` and `anchorNode` are left to
        // the mapper, both being checkboxes.
        expect(layout.rows).withContext(type).toEqual([['address', 'location'], ['timePeriod']]);
        expect(layout.hints.timePeriod).withContext(type).toBeTruthy();
        // No default and no floor: the mapper already seeds every `timePeriod` with five minutes.
        expect(layout.defaults).withContext(type).toBeUndefined();
      });
  });

  it('gives every mesh attribute a value and a label, and no duplicates', () => {
    let total = 0;
    meshPoints.forEach(([modelType, layout]) => {
      const items = layout.options.attributeId;
      total += items.length;
      items.forEach(item => {
        expect(item.value).withContext(modelType).toBeTruthy();
        expect(item.label).withContext(modelType).toBeTruthy();
        // A label that is still a translation key means the bundle gap (D111) leaked into our list.
        expect(item.label).withContext(`${modelType} ${item.value}`).not.toContain('dsEdit.');
      });
      expect(new Set(items.map(item => item.value)).size).withContext(modelType).toBe(items.length);
    });
    // 104 across the 24 mesh devices, the thermostat's 9, the mesh controller's 1, the four light
    // controllers' 7 + 2 + 8 + 4, and the three asset tags' 4 + 3 + 3. The current sensor's 16 are
    // counted by its own spec below, because its point form is not this one.
    expect(total).toBe(145);
  });

  it('gives the current sensor the family source and a point form of its own', () => {
    expect(GATEWAY_FORM_LAYOUTS['CURRENT_SENSOR.DS'].rows).toEqual([['address', 'location']]);
    const point = GATEWAY_FORM_LAYOUTS['CURRENT_SENSOR.PL'];
    // Sixteen attributes over two enums, both loaded into the one shared `ATTRIBUTE_CODES` table.
    // These counts and orderings are regression guards over our own table, not proof against the
    // gateway: nothing here can detect an attribute the enum has and we never transcribed.
    expect(point.options.attributeId.length).toBe(16);
    expect(point.options.attributeId.map(item => item.value)).toContain('TOTAL_POWER');
    // The gateway answers FREQUENCY with `new Random().doubles(49.9, 50.1)`, so the label says so.
    // Same treatment as `32_A`: the value stays because a point may hold it, the label stops it
    // being read as a reading. D128.
    expect(point.options.attributeId.find(item => item.value === 'FREQUENCY').label)
      .toBe('Frequency (simulated)');
    // `ctId` scales every reading through `CTConversionUtil.ctConversionTable`, so it is the one
    // field an installer has to be able to correct. `phaseId` is routing and stays locked.
    expect(point.readonly).toEqual(['attributeId', 'dataType', 'phaseId']);
    expect(point.readonly).not.toContain('ctId');
    expect(point.hints.ctId).toBeTruthy();
    // Sorted by rating rather than in the enum's order, which puts 120 A after 1200 A. All eight
    // values are kept, including the one that does not work, because a stored point may hold it.
    expect(point.options.ctId.map(item => item.value))
      .toEqual(['32_A', '64_A', '100_A', '120_A', '250_A', '500_A', '800_A', '1200_A']);
    // `CTConversionUtil.ctConversionTable` has no branch for 32 A, so it returns its -1.000
    // initialiser and the point reports a constant -0.001 A. The gateway accepts the value, so the
    // only thing a layout can do about it is say so. D125.
    expect(point.options.ctId.find(item => item.value === '32_A').label)
      .toBe('32 A (not supported)');
    expect(point.hints.ctId).toContain('32 A');
    // A current sensor measures, so nothing on the gateway ever has a `true` to put in `settable`.
    expect(point.hidden).toContain('settable');
  });
});
