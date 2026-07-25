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
import { PageOrientation, PageSize, PageSizeDimensions } from '@shared/models/report-configuration.models';

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
});
