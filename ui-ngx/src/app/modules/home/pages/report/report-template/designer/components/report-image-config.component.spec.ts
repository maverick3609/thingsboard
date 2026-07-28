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
// report-rich-text-config.component.spec.ts / report-table-config.component.spec.ts: the CVA
// plumbing only touches the injected UntypedFormBuilder, never the DOM (in particular, never
// tb-gallery-image-input or tb-report-datasource, both of which need a full TestBed +
// HttpClient/Store/MatDialog to render), so this proves the writeValue -> form -> propagateChange
// wiring - including the backgroundBorder flatten/unflatten mapping AND Task IMG's new
// dataSource <-> dataSources[0] mapping - without compiling either child's template.
// aliasController is asserted only as a plain pass-through Input (never handed to a live
// tb-report-datasource here) - the same guarantee report-table-config.component.spec.ts already
// relies on for its own aliasController Input.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportImageConfigComponent } from './report-image-config.component';
import {
  DataSource,
  DataSourceType,
  ImageAlignment,
  ImageComponent,
  ImageSourceType,
  ImageWidthType
} from '@shared/models/report-configuration.models';

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

  it('keeps dataSources[0] as previously written when an unrelated field is edited (Task IMG: the dataSource control now round-trips live through writeValue/toImageComponent, but an edit that never touches it leaves its value unchanged)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(initial);
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      dataSources: initial.dataSources
    }));
  });

  it('leaves every optional field null - except dataSource, which seeds a fresh empty device datasource (the blank shape tb-report-datasource expects) - when written with a bare {type: IMAGE, sourceType: image}', () => {
    const component = newComponent();

    component.writeValue({ type: 'IMAGE', sourceType: ImageSourceType.IMAGE });

    expect(component.imageFormGroup.value).toEqual({
      sourceType: ImageSourceType.IMAGE,
      imageUrl: null,
      dataSource: { type: DataSourceType.DEVICE, dataKeys: [] },
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

  // ---- Task IMG: ENTITY_KEY source config (the C1 gap fix) --------------------------------

  const entityKeyDataSources: DataSource[] = [
    { type: DataSourceType.DEVICE, deviceId: 'device-9', dataKeys: [{ name: 'imageUrl', label: 'Image URL', type: 'attribute' }] }
  ];

  const entityKeyFixture: ImageComponent = {
    type: 'IMAGE',
    sourceType: ImageSourceType.ENTITY_KEY,
    imageUrl: null,
    dataSources: entityKeyDataSources,
    widthType: ImageWidthType.ORIGINAL,
    customWidth: undefined,
    alignment: ImageAlignment.LEFT,
    margins: null,
    paddings: null,
    background: null,
    borderWidth: null,
    borderRadius: null,
    borderColor: null
  };

  it('round-trips an ENTITY_KEY-source component: dataSources[0] (with its data key) preserved on an unrelated edit', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(entityKeyFixture);
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange).toHaveBeenCalledWith({
      ...entityKeyFixture,
      alignment: ImageAlignment.RIGHT
    });
  });

  it('collapses a loaded config with more than one dataSource down to index 0 (PE-faithful narrowing - ImageComponent is single-source, the renderer only ever reads dataSources[0].dataKeys[0])', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    const second: DataSource = { type: DataSourceType.ENTITY, entityAliasId: 'a1', dataKeys: [{ name: 'other', label: 'Other', type: 'attribute' }] };
    component.writeValue({ ...entityKeyFixture, dataSources: [entityKeyDataSources[0], second] });
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      dataSources: [entityKeyDataSources[0]]
    }));
  });

  it('preserves imageUrl when switching sourceType from IMAGE to ENTITY_KEY - only the visible editor changes, both source values ride along (round-trip fidelity)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue({ type: 'IMAGE', sourceType: ImageSourceType.IMAGE, imageUrl: 'tb-image;/api/images/tenant/logo' });
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ sourceType: ImageSourceType.ENTITY_KEY });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      sourceType: ImageSourceType.ENTITY_KEY,
      imageUrl: 'tb-image;/api/images/tenant/logo'
    }));
  });

  it('preserves dataSources when switching sourceType from ENTITY_KEY to IMAGE - only the visible editor changes, both source values ride along (round-trip fidelity)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(entityKeyFixture);
    component.registerOnChange(onChange);

    component.imageFormGroup.patchValue({ sourceType: ImageSourceType.IMAGE });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      sourceType: ImageSourceType.IMAGE,
      dataSources: entityKeyFixture.dataSources
    }));
  });

  it('exposes isImageSource/isEntityKeySource, driving the template *ngIf for each source type', () => {
    const component = newComponent();

    component.writeValue({ type: 'IMAGE', sourceType: ImageSourceType.IMAGE });
    expect(component.isImageSource).toBe(true);
    expect(component.isEntityKeySource).toBe(false);

    component.writeValue({ type: 'IMAGE', sourceType: ImageSourceType.ENTITY_KEY });
    expect(component.isImageSource).toBe(false);
    expect(component.isEntityKeySource).toBe(true);
  });

  it('exposes aliasController as a plain pass-through Input (wired by the shell), and imageDataSourceTypes narrowed to device/entity (Task 11 carry - count types yield no image value)', () => {
    const component = newComponent();

    expect(component.aliasController).toBeUndefined();
    const aliasController: any = { fake: true };
    component.aliasController = aliasController;
    expect(component.aliasController).toBe(aliasController);

    expect(component.imageDataSourceTypes).toEqual([DataSourceType.DEVICE, DataSourceType.ENTITY]);
  });
});
