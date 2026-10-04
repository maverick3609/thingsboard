// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { bitsToFloat, CONTROLLER_CONFIG_SECTIONS, escapeCell, firmwareVersionOf, floatToBits,
  POINT_FLAG_WRITABLE, POINTS_CONFIG_SECTION, pointWriteBody, sameFirmwareRelease,
  WRITABLE_POINT_TYPES, writablePointIds } from './inferrix-controller.models';

describe('Inferrix controller config models', () => {

  describe('deadband bit conversion', () => {

    // The controller stores a publish deadband as the raw 32 bits of an IEEE-754 float and never
    // parses a decimal, so getting this wrong silently configures a different deadband than the
    // operator typed.
    it('encodes 0.15 as 0x3E19999A', () => {
      expect(floatToBits(0.15)).toBe(0x3E19999A);
      expect(floatToBits(0.15)).toBe(1041865114);
    });

    it('round-trips the values an operator actually types', () => {
      [0, 0.1, 0.5, 1, 2.5, 100, 65535].forEach(value => {
        expect(bitsToFloat(floatToBits(value))).toBeCloseTo(value, 3);
      });
    });

    it('decodes a pattern with the sign bit set', () => {
      // Not a legal deadband, but the decoder must not read the pattern as a negative array index
      // or lose the top bit through JavaScript's signed bitwise operators.
      expect(bitsToFloat(0xBF800000)).toBe(-1);
    });
  });

  describe('section specs', () => {

    it('gives every section a key field that is one of its own fields', () => {
      CONTROLLER_CONFIG_SECTIONS.forEach(section => {
        const idField = section.fields.find(field => field.key === section.idField);
        expect(idField).withContext(`${section.key}.${section.idField}`).toBeDefined();
        expect(idField.keyField).withContext(`${section.key}.${section.idField}`).toBe(true);
      });
    });

    it('only shows columns the section actually has fields for', () => {
      CONTROLLER_CONFIG_SECTIONS.forEach(section => {
        const keys = section.fields.map(field => field.key);
        section.columns.forEach(column =>
          expect(keys).withContext(`${section.key}.${column}`).toContain(column));
      });
    });

    // A bitmask renders as checkboxes with no field to type into, so an undefined default leaves
    // the record with no value at all. What the default *is* is the section's business:
    // `mqtt-policies.trigger` defaults to 2 (`trigger-on-change`) on purpose, which is why this
    // asks for a number rather than for zero.
    it('defaults every bitmask field, which has no input to type a value into', () => {
      CONTROLLER_CONFIG_SECTIONS.forEach(section =>
        section.fields.filter(field => field.type === 'flags').forEach(field =>
          expect(typeof field.defaultValue).withContext(`${section.key}.${field.key}`)
            .toBe('number')));
    });
  });

  describe('escapeCell', () => {

    it('neutralises markup a controller could put in its own attributes', () => {
      expect(escapeCell('<img src=x onerror=alert(1)>'))
        .toBe('&lt;img src=x onerror=alert(1)&gt;');
      expect(escapeCell(`" onmouseover='x'`)).toBe('&quot; onmouseover=&#39;x&#39;');
      expect(escapeCell('a & b')).toBe('a &amp; b');
    });

    it('renders absent values as empty and keeps 0', () => {
      expect(escapeCell(null)).toBe('');
      expect(escapeCell(undefined)).toBe('');
      expect(escapeCell(0)).toBe('0');
    });

  });

  describe('point writes', () => {

    it('sends bools and whole numbers as v', () => {
      expect(pointWriteBody('do', true)).toEqual({type: 'do', v: true});
      expect(pointWriteBody('rtu', 1500)).toEqual({type: 'rtu', v: 1500});
      expect(pointWriteBody('ao', -2147483648)).toEqual({type: 'ao', v: -2147483648});
    });

    it('sends fractions, and whole numbers too wide for an int32, as float bits', () => {
      // The device parses v as a bool or an int32 only; anything else there is a bad_field.
      expect(pointWriteBody('ao', 0.15)).toEqual({type: 'ao', v_bits: 0x3E19999A});
      expect(pointWriteBody('ao', 2147483648)).toEqual({type: 'ao', v_bits: floatToBits(2147483648)});
    });
  });

  describe('firmware versions', () => {

    it('treats the numeric 0 older announces sent as no version at all', () => {
      expect(firmwareVersionOf(0)).toBeNull();
      expect(firmwareVersionOf('')).toBeNull();
      expect(firmwareVersionOf(undefined)).toBeNull();
      expect(firmwareVersionOf('0.1.15+0')).toBe('0.1.15+0');
    });

    it('compares releases the way MCUboot ranks them, ignoring the build number', () => {
      expect(sameFirmwareRelease('0.1.16+0', '0.1.16+7')).toBeTrue();
      expect(sameFirmwareRelease('0.1.15+0', '0.1.16+0')).toBeFalse();
      expect(sameFirmwareRelease('0.1.1+0', '0.1.16+0')).toBeFalse();
      expect(sameFirmwareRelease('—', '—')).toBeFalse();
    });
  });

  describe('which points accept a write', () => {

    // The `points` section of a real bench board (firmware 0.1.17), trimmed to the records that
    // matter. Its names are the operator's own: `RTU DI1` is a discrete input and can never be
    // written, yet it shares the live `rtu` type with `RTU DO1`, which can.
    const BENCH_POINTS = [
      {point_id: 8, name: 'DI8', source: 0, flags: 0},
      {point_id: 9, name: 'DO1', source: 1, flags: 1},
      {point_id: 11, name: 'DO3', source: 1, flags: 0},
      {point_id: 19, name: 'AO1_raw', source: 3, flags: 1},
      {point_id: 22, name: 'AO3', source: 3, flags: 0},
      {point_id: 26, name: 'RTU DI1', source: 4, flags: 0},
      {point_id: 30, name: 'RTU DO1', source: 4, flags: 1},
      {point_id: 34, name: 'Frequency EM', source: 4, flags: 0}
    ];

    it('takes the writable bit from the config, not the point class', () => {
      expect(writablePointIds(BENCH_POINTS)).toEqual(new Set([9, 19, 30]));
    });

    // The regression this was written for: every one of these is in a class the live screen used to
    // treat as writable, and not one of them is.
    it('refuses the RTU inputs and the unflagged on-board outputs', () => {
      const writable = writablePointIds(BENCH_POINTS);
      [26, 34, 11, 22].forEach(id => expect(writable.has(id)).toBeFalse());
    });

    it('reads the refresh bit as no permission at all', () => {
      expect(writablePointIds([{point_id: 1, flags: 2}])).toEqual(new Set());
      expect(writablePointIds([{point_id: 1, flags: 3}])).toEqual(new Set([1]));
    });

    // Absent is not permission: a record with no flags, a null section and a bad record must all
    // come back read-only rather than throw or default to writable.
    it('treats a missing flags field, and a missing section, as read-only', () => {
      expect(writablePointIds([{point_id: 1}])).toEqual(new Set());
      expect(writablePointIds([null, undefined])).toEqual(new Set());
      expect(writablePointIds(null)).toEqual(new Set());
    });

    it('keeps the class list as a necessary condition for the fallback path', () => {
      // `di` and `ai` can never take a write, so the fallback must still refuse them outright.
      expect(WRITABLE_POINT_TYPES).not.toContain('di');
      expect(WRITABLE_POINT_TYPES).not.toContain('ai');
      expect(WRITABLE_POINT_TYPES).toContain('rtu');
    });

    it('resolves the points config section the live tab reads', () => {
      expect(POINTS_CONFIG_SECTION).toBeTruthy();
      expect(POINTS_CONFIG_SECTION.readSection).toBe('points');
      expect(POINT_FLAG_WRITABLE).toBe(1);
      // The flags field's writable checkbox and the bit this module filters on must be one value.
      const flags = POINTS_CONFIG_SECTION.fields.find(field => field.key === 'flags');
      expect(flags.bits.map(bit => bit.value)).toContain(POINT_FLAG_WRITABLE);
    });
  });
});
