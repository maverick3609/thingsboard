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

// Plain instantiation (no TestBed), mirroring report-table-settings.component.spec.ts /
// report-image-config.component.spec.ts / report-datasource.component.spec.ts: the CVA plumbing
// only touches the injected UntypedFormBuilder, never the DOM, so this proves the
// writeValue -> form -> toComponent -> propagateChange wiring - including the per-`componentType`
// output-shape branching (task-11-brief.md's hard requirement that ALARM_TABLE never emits a
// `dataSources` key) - without compiling tb-report-datasource(s)/tb-entity-alias-select/
// tb-filter-select's own templates or requiring a live aliasController. This also sidesteps the
// karma-safe-spec trap (task-11-interfaces.md's "Karma-safe spec pattern" section): a spec that
// never calls TestBed.configureTestingModule/compileComponents never asks Angular to compile any
// template at all, so it can never reach EntityAliasDialogComponent/FilterDialogComponent's
// declaring HomeComponentsModule - a strictly narrower (and precedent-matching) guarantee than a
// TestBed+NO_ERRORS_SCHEMA shallow render would give.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportTableConfigComponent } from './report-table-config.component';
import {
  AlarmTableComponent,
  DataSourceType,
  EntityTableComponent,
  ReportComponent,
  TableSortDirection,
  TimeseriesTableComponent
} from '@shared/models/report-configuration.models';
import { AlarmSearchStatus } from '@shared/models/alarm.models';
import { widgetType } from '@shared/models/widget.models';

describe('ReportTableConfigComponent', () => {

  function newComponent(componentType: ReportComponent['type']): ReportTableConfigComponent {
    const component = new ReportTableConfigComponent(new UntypedFormBuilder());
    component.componentType = componentType;
    component.ngOnInit();
    return component;
  }

  // task-11-brief.md Step 1, fixture 1.
  const entityTableFixture: EntityTableComponent = {
    type: 'ENTITY_TABLE',
    dataSources: [{ type: DataSourceType.ENTITY, entityAliasId: 'a1', dataKeys: [{ name: 'name', type: 'entityField', label: 'Name' }] }],
    showTableHeading: true,
    tableHeading: { text: 'Entities', height: 40 },
    tableSortOrder: { column: 'Name', direction: TableSortDirection.ASC },
    margins: { top: 20, right: 0, bottom: 0, left: 0 },
    paddings: null,
    background: '#ffffff',
    borderWidth: 1,
    borderRadius: 2,
    borderColor: '#dddddd'
  };

  // task-11-brief.md Step 1, fixture 2. `alarmSource`/`timewindow`, NEVER `dataSources`
  // (AlarmTableComponent.java's getDataSources() is derived+@JsonIgnore'd).
  const alarmTableFixture: AlarmTableComponent = {
    type: 'ALARM_TABLE',
    alarmSource: {
      type: DataSourceType.ENTITY,
      entityAliasId: 'a2',
      alarmFilterConfig: { statusList: [AlarmSearchStatus.ACTIVE] },
      dataKeys: [{ name: 'createdTime', type: 'alarm', label: 'Created time' }]
    },
    timewindow: { history: { historyType: 0, timewindowMs: 86400000 } },
    showTableHeading: false,
    tableHeading: { text: 'Alarms' },
    tableSortOrder: { column: 'Created time', direction: TableSortDirection.DESC },
    margins: { top: 20, right: 0, bottom: 0, left: 0 },
    paddings: null,
    background: null,
    borderWidth: null,
    borderRadius: null,
    borderColor: null
  };

  // task-11-brief.md Step 1, fixture 3.
  const timeSeriesTableFixture: TimeseriesTableComponent = {
    type: 'TIME_SERIES_TABLE',
    dataSources: [{ type: DataSourceType.ENTITY, entityAliasId: 'a1', dataKeys: [{ name: 'temperature', type: 'timeseries', label: 'Temperature' }] }],
    timewindow: { history: { historyType: 0, timewindowMs: 3600000 } },
    showTimestamp: true,
    timestampLabel: 'T',
    timestampPattern: 'HH:mm',
    timestampColumnSettings: { type: 'COLUMN', columnWidth: '120px' },
    showTableHeading: true,
    tableHeading: { text: '${entityName}' },
    tableSortOrder: { column: 'Timestamp', direction: TableSortDirection.DESC },
    margins: { top: 20, right: 0, bottom: 0, left: 0 },
    paddings: null,
    background: null,
    borderWidth: null,
    borderRadius: null,
    borderColor: null
  };

  it('round-trips an ENTITY_TABLE component, with no alarmSource/timewindow/showTimestamp keys fabricated', () => {
    const component = newComponent('ENTITY_TABLE');
    component.writeValue(entityTableFixture);
    let emitted: EntityTableComponent;
    component.registerOnChange(v => emitted = v as EntityTableComponent);

    component.formGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(entityTableFixture);
    expect('alarmSource' in emitted).toBe(false);
    expect('timewindow' in emitted).toBe(false);
    expect('showTimestamp' in emitted).toBe(false);
    expect('timestampLabel' in emitted).toBe(false);
    expect('timestampPattern' in emitted).toBe(false);
    expect('timestampColumnSettings' in emitted).toBe(false);
  });

  it('round-trips an ALARM_TABLE component: emits alarmSource + timewindow, never a dataSources key', () => {
    const component = newComponent('ALARM_TABLE');
    component.writeValue(alarmTableFixture);
    let emitted: AlarmTableComponent;
    component.registerOnChange(v => emitted = v as AlarmTableComponent);

    component.formGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(alarmTableFixture);
    expect(emitted.alarmSource).toEqual(alarmTableFixture.alarmSource);
    expect(emitted.timewindow).toEqual(alarmTableFixture.timewindow);
    expect('dataSources' in emitted).toBe(false);
    expect('showTimestamp' in emitted).toBe(false);
    expect('timestampColumnSettings' in emitted).toBe(false);
  });

  it('round-trips a TIME_SERIES_TABLE component: dataSources + timewindow + timestamp fields, never an alarmSource key', () => {
    const component = newComponent('TIME_SERIES_TABLE');
    component.writeValue(timeSeriesTableFixture);
    let emitted: TimeseriesTableComponent;
    component.registerOnChange(v => emitted = v as TimeseriesTableComponent);

    component.formGroup.patchValue({}); // trigger valueChanges

    expect(emitted).toEqual(timeSeriesTableFixture);
    expect('alarmSource' in emitted).toBe(false);
  });

  it('leaves every field null - not a forced default - when written with a bare {type: ENTITY_TABLE}', () => {
    const component = newComponent('ENTITY_TABLE');

    component.writeValue({ type: 'ENTITY_TABLE' });

    expect(component.formGroup.value).toEqual({
      dataSources: null,
      alarmSource: null,
      timewindow: null,
      tableSettings: { showTableHeading: null, tableHeading: null, tableSortOrder: null },
      showTimestamp: null,
      timestampLabel: null,
      timestampPattern: null,
      margins: null,
      paddings: null,
      backgroundBorder: { background: null, borderWidth: null, borderRadius: null, borderColor: null }
    });
  });

  it('does not eagerly propagate on writeValue', () => {
    const component = newComponent('ENTITY_TABLE');
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(entityTableFixture);

    expect(onChange).not.toHaveBeenCalled();
  });

  it('carries timestampColumnSettings through untouched on every TIME_SERIES_TABLE edit, even for fields this panel does not expose a sub-editor for', () => {
    const component = newComponent('TIME_SERIES_TABLE');
    component.writeValue(timeSeriesTableFixture);
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.formGroup.patchValue({ showTimestamp: false });

    expect(onChange).toHaveBeenCalledWith(jasmine.objectContaining({
      timestampColumnSettings: timeSeriesTableFixture.timestampColumnSettings
    }));
  });

  it('exposes componentType/aliasController as plain pass-through Inputs (wired by the shell)', () => {
    const component = newComponent('ALARM_TABLE');

    expect(component.componentType).toBe('ALARM_TABLE');
    expect(component.aliasController).toBeUndefined();

    const aliasController: any = { fake: true };
    component.aliasController = aliasController;

    expect(component.aliasController).toBe(aliasController);
  });

  it('narrows the datasource-type picker to device/entity for tables (Task 8 carry - ENTITY_COUNT/ALARM_COUNT yield no table rows)', () => {
    const component = newComponent('ENTITY_TABLE');

    expect(component.tableDataSourceTypes).toEqual([DataSourceType.DEVICE, DataSourceType.ENTITY]);
  });

  it('selects widgetType.timeseries for TIME_SERIES_TABLE data keys and widgetType.latest otherwise', () => {
    const entityComponent = newComponent('ENTITY_TABLE');
    expect(entityComponent.dataWidgetType).toBe(widgetType.latest);

    const timeSeriesComponent = newComponent('TIME_SERIES_TABLE');
    expect(timeSeriesComponent.dataWidgetType).toBe(widgetType.timeseries);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent('ENTITY_TABLE');

    component.setDisabledState(true);
    expect(component.formGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.formGroup.disabled).toBe(false);
  });
});
