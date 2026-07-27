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

// Plain instantiation (no TestBed), mirroring report-datasource.component.spec.ts: the CVA plumbing
// only touches the injected UntypedFormBuilder and the child FormArray, never the DOM - each array
// slot is a bare FormControl holding a whole DataSource object (tb-report-datasource is itself the
// CVA that gives that object meaning), so this spec never needs to render the child component or
// supply a live aliasController.
import { UntypedFormBuilder } from '@angular/forms';
import { ReportDatasourcesComponent } from './report-datasources.component';
import { DataSource, DataSourceType } from '@shared/models/report-configuration.models';
import { widgetType } from '@shared/models/widget.models';

describe('ReportDatasourcesComponent', () => {

  function newComponent(): ReportDatasourcesComponent {
    const component = new ReportDatasourcesComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  const twoElementFixture: DataSource[] = [
    { type: DataSourceType.DEVICE, deviceId: 'd1', dataKeys: [{ name: 'temperature', type: 'timeseries' }] },
    { type: DataSourceType.ENTITY, entityAliasId: 'a1', filterId: 'f1', dataKeys: [{ name: 'name', type: 'entityField' }] }
  ];

  it('round-trips a 2-element DataSource[] through writeValue -> the FormArray -> an emitted change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue(twoElementFixture);

    expect(onChange).not.toHaveBeenCalled();
    expect(component.dsFormArray.length).toBe(2);
    expect(component.dsFormArray.value).toEqual(twoElementFixture);

    // simulate an inner tb-report-datasource re-emitting its own (unchanged) value.
    component.dsFormArray.at(0).setValue(twoElementFixture[0]);

    expect(onChange).toHaveBeenCalledWith(twoElementFixture);
  });

  it('writeValue tolerates an undefined value (new, unconfigured component) - empty array', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.dsFormArray.length).toBe(0);
    expect(component.controls).toEqual([]);
  });

  it('addDataSource appends an empty DataSource and propagates', () => {
    const component = newComponent();
    component.writeValue([twoElementFixture[0]]);
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.addDataSource();

    expect(component.dsFormArray.length).toBe(2);
    expect(component.dsFormArray.value[1]).toEqual({});
    expect(onChange).toHaveBeenCalledWith([twoElementFixture[0], {}]);
  });

  it('removeDataSource removes the item at the given index and propagates', () => {
    const component = newComponent();
    component.writeValue(twoElementFixture);
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.removeDataSource(0);

    expect(component.dsFormArray.length).toBe(1);
    expect(onChange).toHaveBeenCalledWith([twoElementFixture[1]]);
  });

  it('exposes aliasController/widgetType/showAlarmFilter as plain pass-through Inputs (forwarded to each child)', () => {
    const component = newComponent();

    expect(component.widgetType).toBe(widgetType.latest);
    expect(component.showAlarmFilter).toBe(false);

    const aliasController: any = { fake: true };
    component.aliasController = aliasController;
    component.widgetType = widgetType.timeseries;
    component.showAlarmFilter = true;

    expect(component.aliasController).toBe(aliasController);
    expect(component.widgetType).toBe(widgetType.timeseries);
    expect(component.showAlarmFilter).toBe(true);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();
    component.writeValue(twoElementFixture);

    component.setDisabledState(true);
    expect(component.dsFormArray.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.dsFormArray.disabled).toBe(false);
  });

  it('trackByIndex tracks by position', () => {
    const component = newComponent();
    expect(component.trackByIndex(3)).toBe(3);
  });

  // Task 11 carry: pure pass-through, mirroring the aliasController/widgetType/showAlarmFilter test
  // above - undefined by default (every pre-existing test never sets this Input).
  it('exposes allowedDataSourceTypes as a plain pass-through Input (forwarded to each child)', () => {
    const component = newComponent();

    expect(component.allowedDataSourceTypes).toBeUndefined();

    component.allowedDataSourceTypes = [DataSourceType.DEVICE, DataSourceType.ENTITY];

    expect(component.allowedDataSourceTypes).toEqual([DataSourceType.DEVICE, DataSourceType.ENTITY]);
  });
});
