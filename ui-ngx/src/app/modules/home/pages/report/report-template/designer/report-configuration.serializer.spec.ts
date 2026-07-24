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

import { serialize, deserialize } from './report-configuration.serializer';

// PE-shape fixture cross-checked against the backend wire shape (common/data/.../report/configuration/**):
// PageSize/PageOrientation are name-valued enums (no @JsonValue), TextAlignment/FontWeight/BorderType/
// DataSourceType are @JsonValue-backed lowercase strings, and ReportComponentType (EXISTING_PROPERTY
// "type") is name-valued.
const PE_FIXTURE = {
  format: 'PDF', pageSize: 'A4', pageOrientation: 'PORTRAIT',
  pageMargins: { left: 40, right: 40, top: 40, bottom: 40 },
  components: [
    { type: 'HEADING', value: 'Q3', textAlignment: 'center', font: { size: 18, weight: 'bold' } },
    { type: 'DIVIDER', borderType: 'solid', widthPx: 1, color: '#000000' },
    { type: 'ENTITY_TABLE', showTableHeading: true, dataSources: [{ type: 'device', deviceId: 'd1', dataKeys: [] }] }
  ]
};

describe('report-configuration.serializer', () => {
  it('round-trips a PE-shape config byte-compatibly', () => {
    expect(serialize(deserialize(PE_FIXTURE))).toEqual(PE_FIXTURE);
  });

  it('preserves component subtrees C1 does not edit (ENTITY_TABLE) verbatim', () => {
    const out = serialize(deserialize(PE_FIXTURE));
    expect(out.components[2]).toEqual(PE_FIXTURE.components[2]);
  });

  it('defaults components to [] when missing so panels can safely iterate', () => {
    const model = deserialize({ format: 'PDF' });
    expect(model.components).toEqual([]);
  });

  it('serialize throws if the root format discriminator is missing', () => {
    expect(() => serialize({ components: [] } as any)).toThrowError(/format/);
  });

  it('serialize throws if a component type discriminator is missing', () => {
    expect(() => serialize({ format: 'PDF', components: [{ value: 'x' }] } as any)).toThrowError(/type/);
  });
});
