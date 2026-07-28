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

// Plain instantiation (no TestBed), matching this designer's established convention
// (report-canvas.component.spec.ts, report-divider-config.component.spec.ts, etc.): the CVA
// plumbing only touches the injected UntypedFormBuilder plus the two plain @Output EventEmitters,
// never the DOM/a real nested tb-report-canvas, so a `new`'d instance proves the writeValue ->
// toggleFormGroup -> propagateChange wiring and the componentSelected/componentsChanged
// re-emission methods (reEmitSelection/onComponentsChange/onFirstPageChange) directly, without
// compiling the template.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportHeaderFooterComponent } from './report-header-footer.component';
import { HeaderFooter, ReportComponent } from '@shared/models/report-configuration.models';

describe('ReportHeaderFooterComponent', () => {

  function newComponent(): ReportHeaderFooterComponent {
    const component = new ReportHeaderFooterComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('round-trips a HeaderFooter with a nested firstPage through writeValue, without cloning it, reflecting both toggles', () => {
    const component = newComponent();
    const value: HeaderFooter = {
      enabled: true,
      components: [{ type: 'HEADING', value: 'Q3 header' }],
      firstPage: { enabled: false, components: [{ type: 'DIVIDER' }] }
    };

    component.writeValue(value);

    // Same reference, not a copy: the nested canvas mutates value.components in place (like the
    // shell's body canvas), which only stays correct if writeValue doesn't defensively clone.
    expect(component.value).toBe(value);
    expect(component.toggleFormGroup.value).toEqual({ enabled: true, firstPageEnabled: true });
  });

  it('defaults to a disabled, empty HeaderFooter when written with no value', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.value).toEqual({ enabled: false, components: [] });
    expect(component.toggleFormGroup.value).toEqual({ enabled: false, firstPageEnabled: false });
  });

  it('does not propagate a change when writeValue resets the toggles (no CVA feedback loop)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue({ enabled: true, components: [], firstPage: { enabled: true, components: [] } });

    expect(onChange).not.toHaveBeenCalled();
  });

  it('propagates enabled:true when the enabled toggle is switched on by the user', () => {
    const component = newComponent();
    component.writeValue({ enabled: false, components: [] });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.toggleFormGroup.patchValue({ enabled: true });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({ enabled: true }));
  });

  it('fabricates an empty firstPage the first time firstPageEnabled is switched on', () => {
    const component = newComponent();
    component.writeValue({ enabled: true, components: [] });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.toggleFormGroup.patchValue({ firstPageEnabled: true });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      firstPage: { enabled: false, components: [] }
    }));
  });

  it('preserves an existing firstPage across an unrelated enabled toggle (spread, not overwrite)', () => {
    const component = newComponent();
    const firstPage: HeaderFooter = { enabled: true, components: [{ type: 'DIVIDER' }] };
    component.writeValue({ enabled: false, components: [], firstPage });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.toggleFormGroup.patchValue({ enabled: true });

    const emitted = onChange.calls.mostRecent().args[0] as HeaderFooter;
    expect(emitted.firstPage).toEqual(firstPage);
  });

  it('clears firstPage to undefined when firstPageEnabled is switched off', () => {
    const component = newComponent();
    component.writeValue({
      enabled: true,
      components: [],
      firstPage: { enabled: true, components: [{ type: 'DIVIDER' }] }
    });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.toggleFormGroup.patchValue({ firstPageEnabled: false });

    const emitted = onChange.calls.mostRecent().args[0] as HeaderFooter;
    expect(emitted.firstPage).toBeUndefined();
  });

  it('onComponentsChange mutates value.components in place, propagates, and emits componentsChanged', () => {
    const component = newComponent();
    const original: ReportComponent[] = [{ type: 'DIVIDER' }];
    component.writeValue({ enabled: true, components: original });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);
    const onComponentsChanged = jasmine.createSpy('componentsChanged');
    component.componentsChanged.subscribe(onComponentsChanged);

    const updated: ReportComponent[] = [{ type: 'DIVIDER' }, { type: 'PAGE_BREAK' }];
    component.onComponentsChange(updated);

    expect(component.value.components).toBe(updated);
    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({ components: updated }));
    expect(onComponentsChanged).toHaveBeenCalledTimes(1);
  });

  it('onFirstPageChange writes the nested firstPage value back by reference and propagates the outer HeaderFooter', () => {
    const component = newComponent();
    component.writeValue({ enabled: true, components: [], firstPage: { enabled: false, components: [] } });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    const nestedValue: HeaderFooter = { enabled: true, components: [{ type: 'DIVIDER' }] };
    component.onFirstPageChange(nestedValue);

    expect(component.value.firstPage).toBe(nestedValue);
    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({ firstPage: nestedValue }));
  });

  it('reEmitSelection re-emits a component via componentSelected - the shared hop for both the nested canvas and a nested firstPage editor', () => {
    const component = newComponent();
    const onSelected = jasmine.createSpy('componentSelected');
    component.componentSelected.subscribe(onSelected);
    const heading: ReportComponent = { type: 'HEADING', value: 'h' };

    component.reEmitSelection(heading);

    expect(onSelected).toHaveBeenCalledWith(heading);
  });

  it('allowFirstPage defaults to true, so the root header/footer editor offers the "different first page" toggle', () => {
    const component = newComponent();

    expect(component.allowFirstPage).toBe(true);
  });

  it('setDisabledState disables and re-enables the toggle form', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.toggleFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.toggleFormGroup.disabled).toBe(false);
  });
});
