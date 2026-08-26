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
// plumbing only touches the two plain @Output EventEmitters and the `value`/`variant` fields,
// never the DOM or a real nested tb-report-canvas, so a `new`'d instance proves the
// writeValue -> mutation -> propagateChange wiring directly, without compiling the template.
import { ReportHeaderFooterComponent } from './report-header-footer.component';
import { HeaderFooter, ReportComponent } from '@shared/models/report-configuration.models';

describe('ReportHeaderFooterComponent', () => {

  it('keeps the written HeaderFooter by reference, without cloning it', () => {
    const component = new ReportHeaderFooterComponent();
    const value: HeaderFooter = {
      enabled: true,
      components: [{ type: 'HEADING', value: 'Q3 header' }],
      firstPage: { enabled: false, components: [{ type: 'DIVIDER' }] }
    };

    component.writeValue(value);

    // Same reference, not a copy: the nested canvas mutates value.components in place (like the
    // shell's body canvas), which only stays correct if writeValue doesn't defensively clone.
    expect(component.value).toBe(value);
    expect(component.current).toBe(value);
  });

  it('defaults to a disabled, empty HeaderFooter when written with no value', () => {
    const component = new ReportHeaderFooterComponent();

    component.writeValue(undefined);

    expect(component.value).toEqual({ enabled: false, components: [] });
  });

  it('reads the firstPage variant when the toggle points at it', () => {
    const component = new ReportHeaderFooterComponent();
    const firstPage: HeaderFooter = { enabled: true, components: [{ type: 'DIVIDER' }] };
    component.writeValue({ enabled: false, components: [], firstPage });

    component.variant = 'firstPage';

    expect(component.current).toBe(firstPage);
  });

  // The byte-fidelity guarantee (C2 Task 18): an optional the user never touched must not appear in
  // the saved config just because they looked at its tab.
  it('does NOT materialise firstPage merely by switching to its tab', () => {
    const component = new ReportHeaderFooterComponent();
    const value: HeaderFooter = { enabled: true, components: [] };
    component.writeValue(value);
    let propagated: HeaderFooter = null;
    component.registerOnChange(v => propagated = v);

    component.variant = 'firstPage';

    expect(component.current.enabled).toBe(false);
    expect(component.current.components).toEqual([]);
    expect(value.firstPage).toBeUndefined();
    expect(propagated).toBeNull();
  });

  it('materialises firstPage on the first write to it, and propagates the OUTER HeaderFooter', () => {
    const component = new ReportHeaderFooterComponent();
    const value: HeaderFooter = { enabled: true, components: [] };
    component.writeValue(value);
    let propagated: HeaderFooter = null;
    component.registerOnChange(v => propagated = v);
    component.variant = 'firstPage';

    component.toggleEnabled();

    expect(value.firstPage).toEqual({ enabled: true, components: [] });
    expect(propagated).toBe(value);
  });

  it('toggleEnabled flips the CURRENT variant only, in place, and signals componentsChanged', () => {
    const component = new ReportHeaderFooterComponent();
    const value: HeaderFooter = { enabled: false, components: [], firstPage: { enabled: true, components: [] } };
    component.writeValue(value);
    let changed = 0;
    component.componentsChanged.subscribe(() => changed++);

    component.toggleEnabled();

    expect(value.enabled).toBe(true);
    expect(value.firstPage.enabled).toBe(true);
    expect(changed).toBe(1);
  });

  it('onComponentsChange writes into the CURRENT variant and propagates the outer HeaderFooter', () => {
    const component = new ReportHeaderFooterComponent();
    const value: HeaderFooter = { enabled: true, components: [], firstPage: { enabled: true, components: [] } };
    component.writeValue(value);
    component.variant = 'firstPage';
    let propagated: HeaderFooter = null;
    component.registerOnChange(v => propagated = v);
    const components: ReportComponent[] = [{ type: 'HEADING', value: 'First page only' }];

    component.onComponentsChange(components);

    expect(value.firstPage.components).toBe(components);
    expect(value.components).toEqual([]);
    expect(propagated).toBe(value);
  });

  it('setDisabledState is reflected for the template to gate the Disable button', () => {
    const component = new ReportHeaderFooterComponent();

    component.setDisabledState(true);
    expect(component.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.disabled).toBe(false);
  });

  it('picks header/footer labels and the matching "disabled" prompt per variant', () => {
    const component = new ReportHeaderFooterComponent();

    expect(component.titleKey).toBe('report.designer.header');
    expect(component.disabledKey).toBe('report.designer.header-disabled');

    component.variant = 'firstPage';
    expect(component.titleKey).toBe('report.designer.first-page-header');
    expect(component.disabledKey).toBe('report.designer.first-page-header-disabled');

    component.header = false;
    expect(component.titleKey).toBe('report.designer.first-page-footer');
    expect(component.disabledKey).toBe('report.designer.first-page-footer-disabled');

    component.variant = 'main';
    expect(component.titleKey).toBe('report.designer.footer');
    expect(component.disabledKey).toBe('report.designer.footer-disabled');
  });
});
