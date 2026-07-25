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

// Plain instantiation (no TestBed), mirroring report-page-settings.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder, never the DOM, so this proves the
// writeValue -> form -> propagateChange wiring - including the backgroundBorder flatten/unflatten
// mapping toDividerComponent() does that T5's page settings didn't need - without compiling the
// template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportDividerConfigComponent } from './report-divider-config.component';
import { BorderLength, BorderType, DividerComponent } from '@shared/models/report-configuration.models';

describe('ReportDividerConfigComponent', () => {

  function newComponent(): ReportDividerConfigComponent {
    const component = new ReportDividerConfigComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: DividerComponent = {
    type: 'DIVIDER',
    length: BorderLength.LONG,
    borderType: BorderType.SOLID,
    widthPx: 2,
    color: '#ff0000',
    margins: { left: 4, right: 4, top: 4, bottom: 4 },
    paddings: { left: 2, right: 2, top: 2, bottom: 2 },
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 0,
    borderColor: '#000000'
  };

  it('propagates onChange with type DIVIDER preserved, and every other field intact, when borderType changes', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(initial);
    expect(onChange).not.toHaveBeenCalled();

    component.dividerFormGroup.patchValue({ borderType: BorderType.DASHED });

    expect(onChange).toHaveBeenCalledWith({
      type: 'DIVIDER',
      length: BorderLength.LONG,
      borderType: BorderType.DASHED,
      widthPx: 2,
      color: '#ff0000',
      margins: initial.margins,
      paddings: initial.paddings,
      background: '#ffffff',
      borderWidth: 1,
      borderRadius: 0,
      borderColor: '#000000'
    });
  });

  it('flattens the nested backgroundBorder control back onto the top-level layout fields', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.dividerFormGroup.patchValue({
      backgroundBorder: { background: '#eeeeee', borderWidth: 3, borderRadius: 2, borderColor: '#111111' }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      background: '#eeeeee',
      borderWidth: 3,
      borderRadius: 2,
      borderColor: '#111111'
    }));
  });

  it('leaves every optional field null - not a forced default - when written with a bare {type: DIVIDER}', () => {
    const component = newComponent();

    component.writeValue({ type: 'DIVIDER' });

    expect(component.dividerFormGroup.value).toEqual({
      length: null,
      borderType: null,
      widthPx: null,
      color: null,
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.dividerFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.dividerFormGroup.disabled).toBe(false);
  });
});
