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

// Plain instantiation (no TestBed), mirroring report-table-config.component.spec.ts /
// report-sub-report-config.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder, never the DOM, so this proves the writeValue -> dashFormGroup -> toComponent ->
// propagateChange round trip - including the security-critical config allowlist - without compiling
// tb-dashboard-autocomplete/tb-timewindow/tb-report-datasources's own templates. This sidesteps the
// karma-safe-spec trap (task-11/13-interfaces.md's "Karma-safe spec pattern" section): a spec that
// never calls TestBed.configureTestingModule/compileComponents never asks Angular to compile any
// template at all.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportDashboardConfigComponent } from './report-dashboard-config.component';
import { DashboardComponent, DataSourceType, ImageAlignment, ImageWidthType } from '@shared/models/report-configuration.models';
import { DashboardReportConfig } from '@shared/models/report.models';

describe('ReportDashboardConfigComponent', () => {

  function newComponent(): ReportDashboardConfigComponent {
    const component = new ReportDashboardConfigComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  // task-14-brief.md Step 1 fixture.
  const dashboardFixture: DashboardComponent = {
    type: 'DASHBOARD',
    config: {
      type: 'png',
      dashboardId: 'd',
      state: 'main',
      timewindow: { selectedTab: 'HISTORY', history: { historyType: 0, timewindowMs: 3600000 } },
      useDashboardTimewindow: true
    },
    dataSources: [{ type: DataSourceType.ENTITY, entityAliasId: 'a1', dataKeys: [{ name: 'temperature', type: 'timeseries', label: 'Temperature' }] }],
    widthType: ImageWidthType.FIT_WIDTH,
    customWidth: undefined,
    alignment: ImageAlignment.CENTER,
    margins: { top: 10, right: 0, bottom: 0, left: 0 },
    paddings: null,
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 2,
    borderColor: '#dddddd'
  };

  it('round-trips a DASHBOARD component (config + dataSources + image sizing + layout), and the emitted config never carries baseUrl/userId/useCurrentUserCredentials', () => {
    const component = newComponent();
    component.writeValue(dashboardFixture);
    let emitted: DashboardComponent;
    component.registerOnChange(v => emitted = v);

    component.dashFormGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(dashboardFixture);
    expect(emitted.type).toBe('DASHBOARD');
    expect(Object.keys(emitted.config)).not.toContain('baseUrl');
    expect(Object.keys(emitted.config)).not.toContain('userId');
    expect(Object.keys(emitted.config)).not.toContain('useCurrentUserCredentials');
  });

  it('does not eagerly propagate on writeValue', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(dashboardFixture);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('leaves every field null - not a forced default - when written with a bare {type: DASHBOARD} (no config key at all)', () => {
    const component = newComponent();

    component.writeValue({ type: 'DASHBOARD' } as DashboardComponent);

    expect(component.dashFormGroup.value).toEqual({
      dashboardId: null,
      state: null,
      timewindow: null,
      useDashboardTimewindow: null,
      dataSources: null,
      widthType: null,
      customWidth: null,
      alignment: null,
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('flattens the nested backgroundBorder control back onto the top-level layout fields', () => {
    const component = newComponent();
    component.writeValue(dashboardFixture);
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.dashFormGroup.patchValue({
      backgroundBorder: { background: '#eeeeee', borderWidth: 3, borderRadius: 4, borderColor: '#111111' }
    });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      background: '#eeeeee',
      borderWidth: 3,
      borderRadius: 4,
      borderColor: '#111111'
    }));
  });

  it('emits customWidth as undefined, not null, when writeValue received it unset (mirrors the IMAGE panel hardening)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(dashboardFixture); // widthType: FIT_WIDTH, customWidth: undefined
    component.registerOnChange(onChange);

    component.dashFormGroup.patchValue({ alignment: ImageAlignment.RIGHT });

    expect(onChange.calls.mostRecent().args[0].customWidth).toBeUndefined();
  });

  it('carries a custom width value through when widthType is CUSTOM', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.writeValue(dashboardFixture);
    component.registerOnChange(onChange);

    component.dashFormGroup.patchValue({ widthType: ImageWidthType.CUSTOM, customWidth: 640 });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      widthType: ImageWidthType.CUSTOM,
      customWidth: 640
    }));
  });

  it('exposes aliasController as a plain pass-through Input (wired by the shell)', () => {
    const component = newComponent();

    expect(component.aliasController).toBeUndefined();
    const aliasController: any = { fake: true };
    component.aliasController = aliasController;
    expect(component.aliasController).toBe(aliasController);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.dashFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.dashFormGroup.disabled).toBe(false);
  });

  describe('the config allowlist (SECURITY - R2a CRITICAL SSRF/token-exfil/spoof finding replicated as FE defense-in-depth)', () => {

    // A config carrying all 3 dangerous fields, as if loaded from a legacy or maliciously-crafted
    // stored template - proves toConfig() STRIPS on load, not merely that a clean config stays clean.
    const maliciousConfig: DashboardReportConfig = {
      type: 'png',
      dashboardId: 'd',
      state: 'main',
      timewindow: { selectedTab: 'HISTORY' },
      useDashboardTimewindow: true,
      baseUrl: 'http://attacker.example/exfil',
      userId: 'some-other-users-id',
      useCurrentUserCredentials: true
    };

    it('strips baseUrl/userId/useCurrentUserCredentials on load, even after editing, while preserving type and the edited value', () => {
      const component = newComponent();
      component.writeValue({ type: 'DASHBOARD', config: maliciousConfig } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      // Simulate a report author editing an unrelated field.
      component.dashFormGroup.patchValue({ state: 'edited-state' });

      expect(Object.keys(emitted.config)).not.toContain('baseUrl');
      expect(Object.keys(emitted.config)).not.toContain('userId');
      expect(Object.keys(emitted.config)).not.toContain('useCurrentUserCredentials');
      expect((emitted.config as any).baseUrl).toBeUndefined();
      expect((emitted.config as any).userId).toBeUndefined();
      expect((emitted.config as any).useCurrentUserCredentials).toBeUndefined();
      // Correctness: type ('png' drives the Playwright-PNG render path) and the edit both survive.
      expect(emitted.config.type).toBe('png');
      expect(emitted.config.state).toBe('edited-state');
      expect(emitted.config.dashboardId).toBe('d');
    });

    it('defaults config.type to png when the incoming config has no type at all', () => {
      const component = newComponent();
      component.writeValue({ type: 'DASHBOARD', config: {} } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      component.dashFormGroup.patchValue({ dashboardId: 'new-id' });

      expect(emitted.config.type).toBe('png');
    });

    it('preserves a non-default incoming type verbatim rather than forcing png', () => {
      const component = newComponent();
      component.writeValue({ type: 'DASHBOARD', config: { type: 'pdf' } } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      component.dashFormGroup.patchValue({ dashboardId: 'new-id' });

      expect(emitted.config.type).toBe('pdf');
    });

    it('preserves timezone and namePattern from the incoming config when present', () => {
      const component = newComponent();
      component.writeValue({
        type: 'DASHBOARD',
        config: { type: 'png', timezone: 'Asia/Kolkata', namePattern: '${dashboardTitle}-report' }
      } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      component.dashFormGroup.patchValue({ dashboardId: 'new-id' });

      expect(emitted.config.timezone).toBe('Asia/Kolkata');
      expect(emitted.config.namePattern).toBe('${dashboardTitle}-report');
    });

    it('omits timezone and namePattern entirely (no key at all) when the incoming config has neither', () => {
      const component = newComponent();
      component.writeValue({ type: 'DASHBOARD', config: { type: 'png' } } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      component.dashFormGroup.patchValue({ dashboardId: 'new-id' });

      expect('timezone' in emitted.config).toBe(false);
      expect('namePattern' in emitted.config).toBe(false);
    });

    it('never spreads the incoming config: an unknown/extra field on it does not leak into the emitted config', () => {
      const component = newComponent();
      component.writeValue({
        type: 'DASHBOARD',
        config: { type: 'png', somethingUnexpected: 'should-not-leak' } as any
      } as DashboardComponent);
      let emitted: DashboardComponent;
      component.registerOnChange(v => emitted = v);

      component.dashFormGroup.patchValue({ dashboardId: 'new-id' });

      expect('somethingUnexpected' in emitted.config).toBe(false);
    });
  });
});
