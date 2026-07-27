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

// Plain instantiation (no TestBed), mirroring report-alarm-filter.component.spec.ts /
// report-data-keys.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder and the toDataSource() pure function, never the DOM - proving the
// writeValue -> toDataSource -> propagateChange wiring and the per-type field-relevance filter
// (task-8-brief.md's contract) WITHOUT compiling tb-entity-alias-select/tb-filter-select/
// tb-report-data-keys/tb-report-alarm-filter's own templates or requiring a live aliasController.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportDatasourceComponent, toDataSource } from './report-datasource.component';
import { DataKey, DataSource, DataSourceType } from '@shared/models/report-configuration.models';
import { AlarmSearchStatus } from '@shared/models/alarm.models';
import { widgetType } from '@shared/models/widget.models';

describe('toDataSource mapping', () => {

  const deviceDataKeys: DataKey[] = [{ name: 'temperature', type: 'timeseries', label: 'Temperature' }];

  // task-8 brief fixture 1: a DEVICE datasource.
  it('round-trips a DEVICE datasource: deviceId + dataKeys only, no entity/filter/alarm fields', () => {
    const fixture: DataSource = { type: DataSourceType.DEVICE, deviceId: 'd1', dataKeys: deviceDataKeys };

    const result = toDataSource(fixture);

    expect(result).toEqual(fixture);
    expect(Object.keys(result).sort()).toEqual(['dataKeys', 'deviceId', 'type']);
  });

  // task-8 brief fixture 2: an ENTITY datasource.
  it('round-trips an ENTITY datasource: entityAliasId + filterId + dataKeys + latestDataKeys, no deviceId/alarmFilterConfig', () => {
    const fixture: DataSource = {
      type: DataSourceType.ENTITY,
      entityAliasId: 'a1',
      filterId: 'f1',
      dataKeys: deviceDataKeys,
      latestDataKeys: [{ name: 'status', type: 'attribute' }]
    };

    const result = toDataSource(fixture);

    expect(result).toEqual(fixture);
    expect(Object.keys(result).sort()).toEqual(['dataKeys', 'entityAliasId', 'filterId', 'latestDataKeys', 'type']);
  });

  // task-8 fixture 3 (caller's round-trip contract): an ALARM_COUNT datasource.
  it('round-trips an ALARM_COUNT datasource: entityAliasId + alarmFilterConfig only, no filterId/dataKeys', () => {
    const fixture: DataSource = {
      type: DataSourceType.ALARM_COUNT,
      entityAliasId: 'a1',
      alarmFilterConfig: { statusList: [AlarmSearchStatus.ACTIVE] }
    };

    const result = toDataSource(fixture);

    expect(result).toEqual(fixture);
    expect(Object.keys(result).sort()).toEqual(['alarmFilterConfig', 'entityAliasId', 'type']);
  });

  it('an ENTITY_COUNT datasource keeps entityAliasId/filterId but omits dataKeys (a scalar count query)', () => {
    const fixture: DataSource = { type: DataSourceType.ENTITY_COUNT, entityAliasId: 'a1', filterId: 'f1' };

    expect(toDataSource(fixture)).toEqual(fixture);
    // even if a stray dataKeys value is present on the form (e.g. left over from a prior type), it
    // must not be fabricated into the ENTITY_COUNT output.
    expect(toDataSource({ ...fixture, dataKeys: deviceDataKeys })).toEqual(fixture);
  });

  it('omits fields irrelevant to the current type even when the form still carries stale values from a previous type selection', () => {
    const staleValue = {
      type: DataSourceType.DEVICE,
      deviceId: 'd1',
      entityAliasId: 'stale-alias',
      filterId: 'stale-filter',
      dataKeys: deviceDataKeys,
      latestDataKeys: null,
      alarmFilterConfig: { statusList: [AlarmSearchStatus.ACTIVE] }
    };

    const result = toDataSource(staleValue);

    expect(result).toEqual({ type: DataSourceType.DEVICE, deviceId: 'd1', dataKeys: deviceDataKeys });
  });

  it('widens alarmFilterConfig relevance to a DEVICE/ENTITY type when showAlarmFilter forces it (ALARM_TABLE alarmSource use)', () => {
    const alarmFilterConfig = { statusList: [AlarmSearchStatus.ACTIVE] };
    const value = { type: DataSourceType.ENTITY, entityAliasId: 'a1', alarmFilterConfig };

    expect(toDataSource(value, false)).toEqual({ type: DataSourceType.ENTITY, entityAliasId: 'a1' });
    expect(toDataSource(value, true)).toEqual({ type: DataSourceType.ENTITY, entityAliasId: 'a1', alarmFilterConfig });
  });

  it('treats a blank/untyped datasource as an empty object', () => {
    expect(toDataSource({})).toEqual({});
    expect(toDataSource({ type: null, deviceId: 'd1' })).toEqual({});
    expect(toDataSource(undefined)).toEqual({});
  });
});

describe('ReportDatasourceComponent', () => {

  const deviceDataKeys: DataKey[] = [{ name: 'temperature', type: 'timeseries', label: 'Temperature' }];

  function newComponent(): ReportDatasourceComponent {
    const component = new ReportDatasourceComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('writeValue populates the form silently (no propagateChange call)', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue({ type: DataSourceType.DEVICE, deviceId: 'd1', dataKeys: deviceDataKeys });

    expect(onChange).not.toHaveBeenCalled();
    expect(component.dsFormGroup.value.deviceId).toBe('d1');
    expect(component.dsFormGroup.value.dataKeys).toEqual(deviceDataKeys);
  });

  it('writeValue tolerates an undefined value (new, unconfigured component)', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.dsFormGroup.value).toEqual({
      type: null,
      deviceId: null,
      entityAliasId: null,
      filterId: null,
      dataKeys: null,
      latestDataKeys: null,
      alarmFilterConfig: null
    });
  });

  it('propagates the filtered DataSource on an inner control change (type switch DEVICE -> ENTITY_COUNT)', () => {
    const component = newComponent();
    component.writeValue({ type: DataSourceType.DEVICE, deviceId: 'd1', dataKeys: deviceDataKeys });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.dsFormGroup.patchValue({ type: DataSourceType.ENTITY_COUNT, entityAliasId: 'a1' });

    // deviceId/dataKeys survive on the FORM (patchValue only touches the named fields) but must not
    // survive in the OUTPUT, since neither is relevant to ENTITY_COUNT (a scalar count query).
    expect(onChange).toHaveBeenCalledWith({ type: DataSourceType.ENTITY_COUNT, entityAliasId: 'a1' });
  });

  it('threads showAlarmFilter into the emitted value via toDataSource', () => {
    const component = newComponent();
    component.showAlarmFilter = true;
    component.writeValue({ type: DataSourceType.DEVICE, deviceId: 'd1' });
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.dsFormGroup.patchValue({ alarmFilterConfig: { statusList: [AlarmSearchStatus.ACTIVE] } });

    expect(onChange).toHaveBeenCalledWith({
      type: DataSourceType.DEVICE,
      deviceId: 'd1',
      alarmFilterConfig: { statusList: [AlarmSearchStatus.ACTIVE] }
    });
  });

  it('exposes aliasController/widgetType/showAlarmFilter as plain pass-through Inputs (wired by Task 11)', () => {
    const component = newComponent();

    expect(component.widgetType).toBe(widgetType.latest);
    expect(component.showAlarmFilter).toBe(false);

    const aliasController: any = { fake: true };
    component.aliasController = aliasController;
    component.widgetType = widgetType.alarm;
    component.showAlarmFilter = true;

    expect(component.aliasController).toBe(aliasController);
    expect(component.widgetType).toBe(widgetType.alarm);
    expect(component.showAlarmFilter).toBe(true);
  });

  it('per-type control-visibility getters match the field-relevance table', () => {
    const component = newComponent();

    component.writeValue({ type: DataSourceType.DEVICE });
    expect(component.isDevice).toBe(true);
    expect(component.showDataKeys).toBe(true);
    expect(component.showEntityAliasSelect).toBe(false);
    expect(component.showFilterSelect).toBe(false);
    expect(component.showAlarmFilterControl).toBe(false);

    component.writeValue({ type: DataSourceType.ENTITY });
    expect(component.isDevice).toBe(false);
    expect(component.showDataKeys).toBe(true);
    expect(component.showEntityAliasSelect).toBe(true);
    expect(component.showFilterSelect).toBe(true);
    expect(component.showAlarmFilterControl).toBe(false);

    component.writeValue({ type: DataSourceType.ENTITY_COUNT });
    expect(component.showDataKeys).toBe(false);
    expect(component.showEntityAliasSelect).toBe(true);
    expect(component.showFilterSelect).toBe(true);
    expect(component.showAlarmFilterControl).toBe(false);

    component.writeValue({ type: DataSourceType.ALARM_COUNT });
    expect(component.showDataKeys).toBe(false);
    expect(component.showEntityAliasSelect).toBe(true);
    expect(component.showFilterSelect).toBe(false);
    expect(component.showAlarmFilterControl).toBe(true);

    component.showAlarmFilter = true;
    component.writeValue({ type: DataSourceType.DEVICE });
    expect(component.showAlarmFilterControl).toBe(true);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.dsFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.dsFormGroup.disabled).toBe(false);
  });
});
