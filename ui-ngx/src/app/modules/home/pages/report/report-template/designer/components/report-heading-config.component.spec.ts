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

// Plain instantiation (no TestBed), mirroring report-divider-config.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder, never the DOM, so this proves the
// writeValue -> form -> propagateChange wiring - including the alignment/backgroundBorder
// flatten/unflatten mapping toHeadingComponent() does - and the dataSources passthrough (HEADING
// is a data-capable component this C1 panel doesn't edit) - without compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportHeadingConfigComponent } from './report-heading-config.component';
import { FontWeight, HeadingComponent, TextAlignment, VerticalAlignment } from '@shared/models/report-configuration.models';

describe('ReportHeadingConfigComponent', () => {

  function newComponent(): ReportHeadingConfigComponent {
    const component = new ReportHeadingConfigComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: HeadingComponent = {
    type: 'HEADING',
    value: 'Report title',
    font: { size: 18, weight: FontWeight.BOLD },
    color: '#222222',
    textAlignment: TextAlignment.LEFT,
    verticalAlignment: VerticalAlignment.MIDDLE,
    height: 40,
    margins: { left: 4, right: 4, top: 4, bottom: 4 },
    paddings: { left: 2, right: 2, top: 2, bottom: 2 },
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 0,
    borderColor: '#000000',
    dataSources: [{ deviceId: 'device-1' }]
  };

  it('propagates onChange with type HEADING preserved, and every other field intact, when value and textAlignment change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(initial);
    expect(onChange).not.toHaveBeenCalled();

    component.headingFormGroup.patchValue({
      value: 'Updated title',
      alignment: { textAlignment: TextAlignment.CENTER, verticalAlignment: VerticalAlignment.MIDDLE }
    });

    expect(onChange).toHaveBeenCalledWith({
      type: 'HEADING',
      value: 'Updated title',
      font: initial.font,
      color: '#222222',
      textAlignment: TextAlignment.CENTER,
      verticalAlignment: VerticalAlignment.MIDDLE,
      height: 40,
      margins: initial.margins,
      paddings: initial.paddings,
      background: '#ffffff',
      borderWidth: 1,
      borderRadius: 0,
      borderColor: '#000000',
      dataSources: initial.dataSources
    });
  });

  it('flattens the nested backgroundBorder control back onto the top-level layout fields', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.headingFormGroup.patchValue({
      backgroundBorder: { background: '#eeeeee', borderWidth: 3, borderRadius: 2, borderColor: '#111111' }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      background: '#eeeeee',
      borderWidth: 3,
      borderRadius: 2,
      borderColor: '#111111'
    }));
  });

  it('carries dataSources through untouched on every edit, even though this panel never edits it', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.headingFormGroup.patchValue({ height: 60 });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      dataSources: initial.dataSources
    }));
  });

  it('leaves every optional field null - not a forced default - when written with a bare {type: HEADING, value}', () => {
    const component = newComponent();

    component.writeValue({ type: 'HEADING', value: '' });

    expect(component.headingFormGroup.value).toEqual({
      value: '',
      font: null,
      color: null,
      alignment: { textAlignment: null, verticalAlignment: null },
      height: null,
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.headingFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.headingFormGroup.disabled).toBe(false);
  });

  // C2 Task 15B. Plain instantiation never compiles the template, so @ViewChild('valueInput') stays
  // undefined here - this exercises insertDynamicField()'s documented FALLBACK (append) path, not the
  // cursor-insert path (which needs a real native <input>, out of scope for this spec convention -
  // see report-heading-config.component.ts's own class header for the full rationale).
  it('appends a dynamic-field token to value via insertDynamicField when no native input is resolvable', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue({ type: 'HEADING', value: 'Page ' });
    component.registerOnChange(onChange);

    component.insertDynamicField('${pageNumber}');

    expect(component.headingFormGroup.get('value').value).toBe('Page ${pageNumber}');
    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({ value: 'Page ${pageNumber}' }));
  });

  it('exposes the 6 dynamic-field tokens for the insert menu', () => {
    const component = newComponent();

    expect(component.dynamicFields.map(f => f.token)).toEqual([
      '${pageNumber}', '${totalPages}', '${reportCreatedTime}', '${entityName}', '${entityLabel}', '${id}'
    ]);
  });
});
