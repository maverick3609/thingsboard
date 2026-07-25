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

// Plain instantiation (no TestBed), mirroring report-heading-config.component.spec.ts /
// report-rich-text-config.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder, never the DOM (in particular, never tb-gallery-image-input, which needs a
// full TestBed + HttpClient/Store/MatDialog to render), so this proves the writeValue -> form ->
// propagateChange wiring - including the backgroundBorder flatten/unflatten mapping
// toImageComponent() does - and the dataSources passthrough (IMAGE is a data-capable component
// this C1 panel doesn't edit) - without compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportImageConfigComponent } from './report-image-config.component';
import { ImageAlignment, ImageComponent, ImageSourceType, ImageWidthType } from '@shared/models/report-configuration.models';

describe('ReportImageConfigComponent', () => {

  function newComponent(): ReportImageConfigComponent {
    const component = new ReportImageConfigComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const initial: ImageComponent = {
    type: 'IMAGE',
    sourceType: ImageSourceType.IMAGE,
    imageUrl: 'tb-image;/api/images/tenant/logo',
    widthType: ImageWidthType.FIT_WIDTH,
    customWidth: undefined,
    alignment: ImageAlignment.CENTER,
    margins: { left: 4, right: 4, top: 4, bottom: 4 },
    paddings: { left: 2, right: 2, top: 2, bottom: 2 },
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 0,
    borderColor: '#000000',
    dataSources: [{ deviceId: 'device-1' }]
  };

  it('does not eagerly propagate on writeValue, and propagates onChange with type IMAGE preserved, and every other field intact, when imageUrl and widthType/customWidth change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(initial);
    expect(onChange).not.toHaveBeenCalled();

    component.imageFormGroup.patchValue({
      imageUrl: 'tb-image;/api/images/tenant/other-logo',
      widthType: ImageWidthType.CUSTOM,
      customWidth: 320
    });

    expect(onChange).toHaveBeenCalledWith({
      type: 'IMAGE',
      sourceType: ImageSourceType.IMAGE,
      imageUrl: 'tb-image;/api/images/tenant/other-logo',
      widthType: ImageWidthType.CUSTOM,
      customWidth: 320,
      alignment: ImageAlignment.CENTER,
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

    component.imageFormGroup.patchValue({
      backgroundBorder: { background: '#eeeeee', borderWidth: 3, borderRadius: 2, borderColor: '#111111' }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      background: '#eeeeee',
      borderWidth: 3,
      borderRadius: 2,
      borderColor: '#111111'
    }));
  });

  it('emits customWidth as undefined, not null, when writeValue received it unset and widthType is not custom', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial); // widthType: FIT_WIDTH, customWidth: undefined
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      customWidth: undefined
    }));
    expect(onChange.calls.mostRecent().args[0].customWidth).toBeUndefined();
  });

  it('carries dataSources through untouched on every edit, even though this panel never edits it', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      dataSources: initial.dataSources
    }));
  });

  it('leaves every optional field null - not a forced default - when written with a bare {type: IMAGE, sourceType: image}', () => {
    const component = newComponent();

    component.writeValue({ type: 'IMAGE', sourceType: ImageSourceType.IMAGE });

    expect(component.imageFormGroup.value).toEqual({
      sourceType: ImageSourceType.IMAGE,
      imageUrl: null,
      widthType: null,
      customWidth: null,
      alignment: null,
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.imageFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.imageFormGroup.disabled).toBe(false);
  });
});
