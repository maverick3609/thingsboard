// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
// Plain instantiation (no TestBed), mirroring report-divider-config.component.spec.ts /
// report-heading-config.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder, never the DOM, so this proves the writeValue -> form -> propagateChange
// wiring - including the alignment flatten/unflatten mapping toCellSettings() does - without
// compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportCellSettingsComponent } from './report-cell-settings.component';
import {
  CellSettings,
  FontStyle,
  FontWeight,
  TextAlignment,
  VerticalAlignment
} from '@shared/models/report-configuration.models';

describe('ReportCellSettingsComponent', () => {

  function newComponent(): ReportCellSettingsComponent {
    const component = new ReportCellSettingsComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('round-trips a full CellSettings', () => {
    const component = newComponent();
    const value: CellSettings = {
      font: { size: 10, weight: FontWeight.BOLD, style: FontStyle.NORMAL, family: 'Roboto' },
      color: '#111',
      backgroundColor: '#eee',
      textAlignment: TextAlignment.CENTER,
      verticalAlignment: VerticalAlignment.MIDDLE
    };
    component.writeValue(value);
    let emitted: CellSettings;
    component.registerOnChange(v => emitted = v);

    component.cellFormGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(value);
  });

  it('flattens the nested alignment control back onto the top-level textAlignment/verticalAlignment fields', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue({ color: '#111' });
    component.registerOnChange(onChange);

    component.cellFormGroup.patchValue({
      alignment: { textAlignment: TextAlignment.RIGHT, verticalAlignment: VerticalAlignment.BOTTOM }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      textAlignment: TextAlignment.RIGHT,
      verticalAlignment: VerticalAlignment.BOTTOM
    }));
  });

  it('leaves every field null - not a forced default - when written with an empty CellSettings', () => {
    const component = newComponent();

    component.writeValue({});

    expect(component.cellFormGroup.value).toEqual({
      font: null,
      color: null,
      backgroundColor: null,
      alignment: { textAlignment: null, verticalAlignment: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.cellFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.cellFormGroup.disabled).toBe(false);
  });
});
