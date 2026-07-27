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

// Plain instantiation (no TestBed), mirroring report-timewindow.component.spec.ts /
// report-alarm-filter.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder and the reportToPlatformDataKeys()/platformToReportDataKeys() pure functions,
// never the DOM, so this proves the writeValue -> mapping -> propagateChange wiring - and the
// mapping functions' own round-trip correctness (task-7-brief.md's contract) - without compiling
// tb-data-keys' Material template or requiring a live aliasController/callbacks (per the brief:
// "your round-trip spec must NOT DOM-render tb-data-keys").
import { UntypedFormBuilder } from '@angular/forms';
import {
  PlatformDataKeyCarrier,
  platformToReportDataKeys,
  ReportDataKeysComponent,
  reportToPlatformDataKeys
} from './report-data-keys.component';
import { DataKey, DataSourceType, FontWeight, FontStyle, TextAlignment, VerticalAlignment } from '@shared/models/report-configuration.models';
import { AggregationType } from '@shared/models/time/time.models';
import { DataKeyType } from '@shared/models/telemetry/telemetry.models';
import { DatasourceType, widgetType } from '@shared/models/widget.models';

describe('report-data-keys mapping', () => {

  // task-7-brief.md's round-trip contract fixture: a key with a full COLUMN ColumnSettings
  // (incl. the type:'COLUMN' discriminator + header/cell CellSettings - T3's deferred coverage),
  // type:'timeseries', postFuncBody, aggregationType.
  const fixtureKeys: DataKey[] = [
    {
      name: 'temperature',
      type: 'timeseries',
      label: 'Temperature',
      decimals: 2,
      units: '°C',
      aggregationType: AggregationType.AVG,
      timewindow: { history: { historyType: 0, timewindowMs: 3600000 } },
      usePostProcessing: true,
      postFuncBody: 'return value * 2;',
      settings: {
        type: 'COLUMN',
        columnWidth: '20%',
        header: {
          font: { size: 12, weight: FontWeight.BOLD, style: FontStyle.NORMAL, family: 'Roboto' },
          color: '#000000',
          backgroundColor: '#ffffff',
          textAlignment: TextAlignment.CENTER,
          verticalAlignment: VerticalAlignment.MIDDLE
        },
        cell: {
          color: '#333333',
          textAlignment: TextAlignment.LEFT
        }
      }
    },
    {
      name: 'status',
      type: 'attribute',
      label: 'Status'
    }
  ];

  it('round-trips the brief fixture through platform and back to report shape, ColumnSettings (incl. the COLUMN discriminator + header/cell) surviving', () => {
    const platformKeys = reportToPlatformDataKeys(fixtureKeys);

    expect(platformKeys[0].name).toBe('temperature');
    expect(platformKeys[0].type).toBe(DataKeyType.timeseries);
    expect(platformKeys[0].label).toBe('Temperature');
    expect(platformKeys[0].decimals).toBe(2);
    expect(platformKeys[0].units).toBe('°C');
    expect(platformKeys[0].aggregationType).toBe(AggregationType.AVG);
    expect(platformKeys[0].postFuncBody).toBe('return value * 2;');
    expect(platformKeys[0].settings).toEqual(fixtureKeys[0].settings);

    const roundTripped = platformToReportDataKeys(platformKeys);
    expect(roundTripped).toEqual(fixtureKeys);
  });

  it('maps the key type dropdown values (entityField|attribute|timeseries|alarm) both ways', () => {
    const types: string[] = ['entityField', 'attribute', 'timeseries', 'alarm'];
    for (const type of types) {
      const platform = reportToPlatformDataKeys([{ name: 'k', type }]);
      expect(platform[0].type).toBe(type as DataKeyType);

      const back = platformToReportDataKeys([{ name: 'k', type: type as DataKeyType }]);
      expect(back[0].type).toBe(type);
    }
  });

  it('carries timewindow (a report-only field with no platform DataKey slot) through untouched', () => {
    const key: DataKey = { name: 'k', type: 'timeseries', timewindow: { timezone: 'UTC' } };

    const platform = reportToPlatformDataKeys([key]);
    expect((platform[0] as PlatformDataKeyCarrier).timewindow).toEqual({ timezone: 'UTC' });

    const back = platformToReportDataKeys(platform);
    expect(back[0].timewindow).toEqual({ timezone: 'UTC' });
  });

  it('does not inject any platform-only field (color/pattern/hidden/inLegend/isAdditional/comparison*) into the report shape', () => {
    const platformKey: PlatformDataKeyCarrier = {
      name: 'k',
      type: DataKeyType.timeseries,
      color: '#ff0000',
      pattern: '#hex',
      hidden: true,
      inLegend: false,
      isAdditional: true,
      origDataKeyIndex: 3,
      comparisonEnabled: true,
      funcBody: 'return 1;'
    };

    const reportKey = platformToReportDataKeys([platformKey])[0];

    // exact-shape assertion: proves both "no platform-only field leaks in" AND "no spurious
    // undefined-valued key is materialized" for every field this fixture didn't set.
    expect(reportKey).toEqual({ name: 'k', type: DataKeyType.timeseries });
    expect(Object.keys(reportKey).sort()).toEqual(['name', 'type']);
  });

  it('guards units/postFuncBody down to plain strings on the way back to the report shape (platform TbUnit/TbFunction are wider unions)', () => {
    const platformKey: PlatformDataKeyCarrier = {
      name: 'k',
      type: DataKeyType.timeseries,
      units: { from: 'W', to: 'kW', formula: 'x/1000' } as any,
      postFuncBody: { body: 'return value;', modules: {} } as any
    };

    const reportKey = platformToReportDataKeys([platformKey])[0];

    expect(reportKey.units).toBeUndefined();
    expect(reportKey.postFuncBody).toBeUndefined();
  });

  it('treats an empty/undefined key list as an empty array in both directions', () => {
    expect(reportToPlatformDataKeys(undefined)).toEqual([]);
    expect(reportToPlatformDataKeys([])).toEqual([]);
    expect(platformToReportDataKeys(undefined)).toEqual([]);
    expect(platformToReportDataKeys([])).toEqual([]);
  });
});

describe('ReportDataKeysComponent', () => {

  function newComponent(): ReportDataKeysComponent {
    const component = new ReportDataKeysComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('converts report -> platform on writeValue without notifying, then platform -> report on an inner tb-data-keys control change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue([{ name: 'temperature', type: 'timeseries', label: 'Temperature' }]);
    expect(onChange).not.toHaveBeenCalled();
    expect(component.dataKeysFormGroup.value.keys).toEqual([
      { name: 'temperature', type: DataKeyType.timeseries, label: 'Temperature' }
    ] as any);

    const changedPlatformKeys: PlatformDataKeyCarrier[] = [
      { name: 'temperature', type: DataKeyType.timeseries, label: 'Temperature (renamed)', color: '#ff0000' }
    ];
    component.dataKeysFormGroup.get('keys').setValue(changedPlatformKeys);

    expect(onChange).toHaveBeenCalledWith([
      { name: 'temperature', type: 'timeseries', label: 'Temperature (renamed)' }
    ] as any);
  });

  it('writeValue tolerates an undefined value (new, unconfigured component)', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.dataKeysFormGroup.value.keys).toEqual([]);
    expect(component.modelValue).toEqual([]);
  });

  it('onColumnSettingsChange updates the report-shape modelValue AND the underlying platform keys control (so a later tb-data-keys emission does not lose it), then propagates', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue([
      { name: 'temperature', type: 'timeseries' },
      { name: 'status', type: 'attribute' }
    ]);

    const settings = { type: 'COLUMN' as const, columnWidth: '30%' };
    component.onColumnSettingsChange(0, settings);

    expect(component.modelValue[0].settings).toEqual(settings);
    expect(component.modelValue[1].settings).toBeUndefined();
    expect(onChange).toHaveBeenCalledWith(component.modelValue);
    // the smuggled-through carrier field must be visible on the SAME index of the platform-facing
    // control's value, or a later unrelated tb-data-keys edit (e.g. editing key 1's label) would
    // stomp key 0's just-set settings back to stale data on its next `keys` emission.
    expect((component.dataKeysFormGroup.value.keys[0] as PlatformDataKeyCarrier).settings).toEqual(settings);

    // simulate tb-data-keys re-emitting (e.g. after an unrelated edit) with the SAME array it now holds.
    onChange.calls.reset();
    component.dataKeysFormGroup.get('keys').setValue(component.dataKeysFormGroup.value.keys);
    expect(onChange).toHaveBeenCalledWith([
      { name: 'temperature', type: 'timeseries', settings },
      { name: 'status', type: 'attribute' }
    ] as any);
  });

  it('maps dataSourceType (report enum) to the platform DatasourceType for tb-data-keys', () => {
    const component = newComponent();

    component.dataSourceType = DataSourceType.ENTITY;
    expect(component.platformDatasourceType).toBe(DatasourceType.entity);

    component.dataSourceType = DataSourceType.ALARM_COUNT;
    expect(component.platformDatasourceType).toBe(DatasourceType.alarmCount);
  });

  it('exposes aliasController/callbacks/entityAliasId/deviceId/widgetType as plain pass-through Inputs (wired by Task 8/11)', () => {
    const component = newComponent();

    expect(component.widgetType).toBe(widgetType.latest);

    const aliasController: any = { fake: true };
    const callbacks: any = { fake: true };
    component.aliasController = aliasController;
    component.callbacks = callbacks;
    component.entityAliasId = 'alias-1';
    component.deviceId = 'device-1';
    component.widgetType = widgetType.alarm;

    expect(component.aliasController).toBe(aliasController);
    expect(component.callbacks).toBe(callbacks);
    expect(component.entityAliasId).toBe('alias-1');
    expect(component.deviceId).toBe('device-1');
    expect(component.widgetType).toBe(widgetType.alarm);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.dataKeysFormGroup.disabled).toBe(true);
    expect(component.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.dataKeysFormGroup.disabled).toBe(false);
    expect(component.disabled).toBe(false);
  });

  it('trackByIndex tracks by position (stabilizes per-key panel identity across settings edits)', () => {
    const component = newComponent();
    expect(component.trackByIndex(2, { name: 'x' } as DataKey)).toBe(2);
  });
});
