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

// Plain instantiation (no TestBed), mirroring report-heading-config.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder, never the DOM (so tb-html's ace editor is
// never constructed here), so this proves the writeValue -> form -> propagateChange wiring -
// including the backgroundBorder flatten/unflatten mapping toRichTextComponent() does - and the
// dataSources passthrough (RICH_TEXT is a data-capable component this C1 panel doesn't edit) -
// without compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportRichTextConfigComponent } from './report-rich-text-config.component';
import { RichTextComponent } from '@shared/models/report-configuration.models';

describe('ReportRichTextConfigComponent', () => {

  function newComponent(): ReportRichTextConfigComponent {
    const component = new ReportRichTextConfigComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: RichTextComponent = {
    type: 'RICH_TEXT',
    value: '<p>Report body</p>',
    margins: { left: 4, right: 4, top: 4, bottom: 4 },
    paddings: { left: 2, right: 2, top: 2, bottom: 2 },
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 0,
    borderColor: '#000000',
    dataSources: [{ deviceId: 'device-1' }]
  };

  it('propagates onChange with type RICH_TEXT preserved, and every other field intact, when value changes', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(initial);
    expect(onChange).not.toHaveBeenCalled();

    component.richTextFormGroup.patchValue({
      value: '<p>Updated body</p>'
    });

    expect(onChange).toHaveBeenCalledWith({
      type: 'RICH_TEXT',
      value: '<p>Updated body</p>',
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

    component.richTextFormGroup.patchValue({
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

    component.richTextFormGroup.patchValue({ value: '<p>Changed</p>' });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      dataSources: initial.dataSources
    }));
  });

  it('leaves every optional field null - not a forced default - when written with a bare {type: RICH_TEXT, value}', () => {
    const component = newComponent();

    component.writeValue({ type: 'RICH_TEXT', value: '' });

    expect(component.richTextFormGroup.value).toEqual({
      value: '',
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.richTextFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.richTextFormGroup.disabled).toBe(false);
  });
});
