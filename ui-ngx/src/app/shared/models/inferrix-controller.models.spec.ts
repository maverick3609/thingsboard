// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { bitsToFloat, CONTROLLER_CONFIG_SECTIONS, escapeCell, firmwareVersionOf, floatToBits, pointWriteBody,
  sameFirmwareRelease } from './inferrix-controller.models';

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

    it('defaults every bitmask field, which has no input to type a value into', () => {
      CONTROLLER_CONFIG_SECTIONS.forEach(section =>
        section.fields.filter(field => field.type === 'flags').forEach(field =>
          expect(field.defaultValue).withContext(`${section.key}.${field.key}`).toBe(0)));
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
});
