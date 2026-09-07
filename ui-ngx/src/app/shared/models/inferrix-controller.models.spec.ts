///
/// Copyright © 2016-2026 The Inferrix Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { bitsToFloat, CONTROLLER_CONFIG_SECTIONS, floatToBits } from './inferrix-controller.models';

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
});
