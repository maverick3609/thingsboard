// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
// Plain instantiation (no TestBed), mirroring report-template-editor.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder, never the DOM, so this proves the
// writeValue -> form -> propagateChange wiring without compiling the Material template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportInsetsComponent } from './report-insets.component';
import { Insets } from '@shared/models/report-configuration.models';

describe('ReportInsetsComponent', () => {

  function newComponent(): ReportInsetsComponent {
    const component = new ReportInsetsComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('propagates the merged Insets when a form field changes after writeValue', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue({ left: 1, right: 2, top: 3, bottom: 4 });
    expect(onChange).not.toHaveBeenCalled();

    component.insetsFormGroup.patchValue({ left: 99 });

    expect(onChange).toHaveBeenCalledWith({ left: 99, right: 2, top: 3, bottom: 4 } as Insets);
  });

  it('defaults every field to 0 when written with no value, so the form never holds undefined', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.insetsFormGroup.value).toEqual({ left: 0, right: 0, top: 0, bottom: 0 });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.insetsFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.insetsFormGroup.disabled).toBe(false);
  });
});
