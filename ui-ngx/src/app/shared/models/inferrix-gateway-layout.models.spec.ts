// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormPropertyType } from '@shared/models/dynamic-form.models';
import { GATEWAY_FORM_LAYOUTS,
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
      'writePriority']);
    Object.entries(GATEWAY_FORM_LAYOUTS).forEach(([modelType, layout]) => {
      [...Object.keys(layout.options ?? {}), ...Object.keys(layout.gatedOptions ?? {})]
        .forEach(id => expect(scalars.has(id)).withContext(`${modelType}.${id}`).toBe(true));
    });
  });

  it('leaves every mesh node locator field read-only, since the radio reports all of them', () => {
    const meshPoint = GATEWAY_FORM_LAYOUTS['VIRTUAL_MESH_NODE.PL'];
    // Everything `VirtualMeshNodePointLocatorModel.toVO` reads, and nothing else: `attributeId`
    // and `type` are the node's own attribute and its wire encoding, `dataType` is how the gateway
    // stores it, and `settable` is what makes a DO writable.
    expect(meshPoint.readonly)
      .toEqual(['dataType', 'settable', 'attributeId', 'type']);
    expect(meshPoint.hidden).toContain('relinquishable');
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

  it('gives a provisioned source nothing for an operator to fill in', () => {
    // What makes suppressing Add correct rather than merely tidy: the locator's every field is
    // read-only, so the form an Add opened would take no input and post a locator the gateway
    // rejects. If a field here ever becomes editable, the button has to come back.
    const layout = GATEWAY_FORM_LAYOUTS['VIRTUAL_MESH_NODE.PL'];
    const editable = ['dataType', 'settable', 'relinquishable', 'configurationDescription',
      'attributeId', 'type']
      .filter(id => !(layout.hidden ?? []).includes(id))
      .filter(id => !(layout.readonly ?? []).includes(id));
    expect(editable).toEqual([]);
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
});
