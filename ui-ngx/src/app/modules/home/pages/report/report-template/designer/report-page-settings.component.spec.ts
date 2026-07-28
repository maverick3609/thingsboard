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

// Plain instantiation (no TestBed), mirroring report-insets.component.spec.ts: the CVA plumbing
// only touches the injected UntypedFormBuilder, never the DOM, so this proves the writeValue ->
// form -> propagateChange wiring (and the dimensionsLabel lookup) without compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportPageSettings, ReportPageSettingsComponent } from './report-page-settings.component';
import {
  HeaderFooter,
  PageOrientation,
  PageSize,
  PageSizeDimensions,
  PdfReportTemplateConfig
} from '@shared/models/report-configuration.models';

describe('ReportPageSettingsComponent', () => {

  function newComponent(): ReportPageSettingsComponent {
    const component = new ReportPageSettingsComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: ReportPageSettings = {
    pageSize: PageSize.A4,
    pageOrientation: PageOrientation.PORTRAIT,
    pageMargins: { left: 40, right: 40, top: 40, bottom: 40 },
    pageBackground: undefined,
    namePattern: undefined,
    timeDataPattern: undefined
  };

  it('propagates onChange when pageSize changes, and a PageSizeDimensions lookup drives the shown dimension hint', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(initial);
    expect(onChange).not.toHaveBeenCalled();

    component.pageSettingsFormGroup.patchValue({ pageSize: PageSize.LETTER });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({ pageSize: PageSize.LETTER }));
    expect(component.dimensionsLabel(PageSize.LETTER))
      .toBe(`${PageSizeDimensions[PageSize.LETTER].width}×${PageSizeDimensions[PageSize.LETTER].height}`);
    expect(component.dimensionsLabel(PageSize.A4))
      .toBe(`${PageSizeDimensions[PageSize.A4].width}×${PageSizeDimensions[PageSize.A4].height}`);
  });

  it('defaults the non-optional fields when written with no value, so the form never holds undefined for them', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.pageSettingsFormGroup.value).toEqual({
      pageSize: PageSize.A4,
      pageOrientation: PageOrientation.PORTRAIT,
      pageMargins: { left: 0, right: 0, top: 0, bottom: 0 },
      pageBackground: null,
      namePattern: null,
      timeDataPattern: null
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.pageSettingsFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.pageSettingsFormGroup.disabled).toBe(false);
  });

  // Task 15A: config.header/config.footer only exist on PdfReportTemplateConfig (CSV has neither
  // field per the model) - showHeaderFooter gates the two new Header/Footer mat-expansion-panels in
  // the template (*ngIf="showHeaderFooter"), mirroring report-sub-report-config.component.ts's own
  // showAvoidPageBreakInside getter for the same "gate a template row off a plain getter, assert the
  // getter directly" reason: this spec is plain instantiation (no TestBed/DOM), so the getter itself
  // is the only testable surface for what the template conditionally renders.
  it('showHeaderFooter is true for a PDF config and false for a CSV config (or no config at all)', () => {
    const component = newComponent();

    component.config = { format: 'PDF', components: [] } as any;
    expect(component.showHeaderFooter).toBe(true);

    component.config = { format: 'CSV', components: [] } as any;
    expect(component.showHeaderFooter).toBe(false);

    component.config = undefined;
    expect(component.showHeaderFooter).toBe(false);
  });

  // Task 15A (discovered via the AOT build, not the design note): the template cannot read/write
  // config.header/config.footer directly - Angular's template type-checker does not narrow `config`
  // off a boolean getter/method call the way plain TypeScript control flow narrows off
  // `config.format === 'PDF'`, so `config.header` inside *ngIf="showHeaderFooter" fails to compile
  // (TS2339, "Property 'header' does not exist on type CsvReportTemplateConfig"). These accessor
  // pairs do the SAME narrowing check internally (in .ts, where it works) and expose an
  // already-narrowed value/setter for the template to bind instead.
  it('header/footer getters read the PDF fields and return undefined for a CSV config (or no config at all)', () => {
    const component = newComponent();
    const headerFooter: HeaderFooter = { enabled: true, components: [{ type: 'DIVIDER' }] };

    component.config = { format: 'PDF', components: [], header: headerFooter } as PdfReportTemplateConfig;
    expect(component.header).toBe(headerFooter);
    expect(component.footer).toBeUndefined();

    component.config = { format: 'CSV', components: [] } as any;
    expect(component.header).toBeUndefined();
    expect(component.footer).toBeUndefined();

    component.config = undefined;
    expect(component.header).toBeUndefined();
  });

  it('header/footer setters write back onto a PDF config, and are a no-op for a CSV config', () => {
    const component = newComponent();
    const newHeader: HeaderFooter = { enabled: true, components: [] };
    const newFooter: HeaderFooter = { enabled: false, components: [] };
    const pdfConfig = { format: 'PDF', components: [] } as PdfReportTemplateConfig;
    component.config = pdfConfig;

    component.header = newHeader;
    component.footer = newFooter;

    expect(pdfConfig.header).toBe(newHeader);
    expect(pdfConfig.footer).toBe(newFooter);

    const csvConfig = { format: 'CSV', components: [] } as any;
    component.config = csvConfig;

    component.header = newHeader;

    expect(csvConfig.header).toBeUndefined();
  });
});
