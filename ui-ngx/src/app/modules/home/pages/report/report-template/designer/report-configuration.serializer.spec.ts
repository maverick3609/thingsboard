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
import { PE_DEFAULT_CONFIGS } from './fixtures/pe-default-configs';

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

// Task 18 - PE byte-fidelity fixtures (C2). Locks serialize(deserialize(x)) === x for every real PE
// palette default (fixtures/pe-default-configs.ts - the 23 component-level literals extracted
// verbatim from the PE jar) plus two hand-built full ReportTemplateConfigModel fixtures (PDF/CSV),
// so a future serializer change that drops/reorders/mangles a field breaks a test instead of
// silently shipping. Every PE_DEFAULT_CONFIGS entry already carries a `type`; wrapping a bare
// component below in `{ format: 'PDF', components: [entry] }` is required only because `serialize`
// needs a full model (format + components[]), not because the component itself is incomplete.
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value));

describe('report-configuration.serializer: PE_DEFAULT_CONFIGS byte-fidelity (per component)', () => {
  Object.keys(PE_DEFAULT_CONFIGS).forEach((key) => {
    it(`round-trips PE_DEFAULT_CONFIGS.${key} byte-compatibly`, () => {
      const wrapped = { format: 'PDF', components: [PE_DEFAULT_CONFIGS[key]] } as any;
      expect(serialize(deserialize(wrapped))).toEqual(wrapped);
    });
  });
});

describe('report-configuration.serializer: full-config fixtures (PE-shaped)', () => {
  // Rich PDF: page settings + 3 body components + header + footer, all real PE component literals
  // (report-configuration.models.ts: PdfReportTemplateConfig = format/pageSize/pageOrientation/
  // pageMargins/header?/footer?). Deep-cloned rather than referenced directly - PE_DEFAULT_CONFIGS.
  // heading/entityTable/dashboard/pageNumber/footer1 are also exercised individually by the
  // per-component describe above, and cloning keeps this fixture's object graph independent of that
  // one (no shared refs across describe blocks).
  const richPdfConfig = {
    format: 'PDF',
    pageSize: 'A4',
    pageOrientation: 'PORTRAIT',
    pageMargins: { left: 40, right: 40, top: 40, bottom: 40 },
    components: [
      clone(PE_DEFAULT_CONFIGS.heading),
      clone(PE_DEFAULT_CONFIGS.entityTable),
      clone(PE_DEFAULT_CONFIGS.dashboard)
    ],
    header: { enabled: true, components: [clone(PE_DEFAULT_CONFIGS.pageNumber)] },
    footer: { enabled: true, components: [clone(PE_DEFAULT_CONFIGS.footer1)] }
  };

  // CSV: CsvReportTemplateConfig (report-configuration.models.ts) carries none of PDF's
  // pageSize/pageOrientation/pageMargins/header/footer - ReportTemplateConfigBase fields only.
  const csvConfig = {
    format: 'CSV',
    components: [clone(PE_DEFAULT_CONFIGS.entityTable)]
  };

  it('round-trips the rich PDF config (deep value)', () => {
    expect(serialize(deserialize(richPdfConfig))).toEqual(richPdfConfig);
  });

  it('round-trips the rich PDF config byte-for-byte (catches key reordering)', () => {
    expect(JSON.stringify(serialize(deserialize(richPdfConfig)))).toBe(JSON.stringify(richPdfConfig));
  });

  it('round-trips the CSV config (deep value)', () => {
    expect(serialize(deserialize(csvConfig))).toEqual(csvConfig);
  });

  it('round-trips the CSV config byte-for-byte (catches key reordering)', () => {
    expect(JSON.stringify(serialize(deserialize(csvConfig)))).toBe(JSON.stringify(csvConfig));
  });

  // F8: font.sizeUnit is an FE-only field (the server ignores it) riding inside `font` on the
  // HEADING-shaped presets - the specific fidelity risk flagged for this task. If a future
  // serializer change ever special-cased `font` (e.g. stripped FE-only sub-fields before save),
  // this assertion is what would catch it.
  it('F8: preserves font.sizeUnit through the round-trip (FE-only field the server ignores)', () => {
    const out = serialize(deserialize(richPdfConfig));
    expect(out.components[0].font.sizeUnit).toBe('pt');
  });

  // Untouched-subtree guard: names one specific deeply nested field (component -> dataSources[0] ->
  // dataKeys[0] -> label) so the intent - deep nesting inside a component subtree, which the
  // round-trip assertions above already check structurally via toEqual - is legible on its own,
  // without cross-referencing the full fixture.
  it('preserves a deeply nested field (entityTable.dataSources[0].dataKeys[0].label)', () => {
    const out = serialize(deserialize(richPdfConfig));
    expect(out.components[1].dataSources[0].dataKeys[0].label).toBe('Name');
  });
});
