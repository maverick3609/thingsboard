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
// report-divider-config.component.spec.ts: the CVA plumbing only touches the injected
// UntypedFormBuilder and the reportToPlatformAlarmFilter()/platformToReportAlarmFilter() pure
// functions, never the DOM, so this proves the writeValue -> mapping -> propagateChange wiring -
// and the mapping functions' own round-trip correctness (task-6-brief.md's contract) - without
// compiling tb-alarm-filter-config's Material/CDK-overlay template.
import { UntypedFormBuilder } from '@angular/forms';
import {
  platformToReportAlarmFilter,
  ReportAlarmFilterComponent,
  reportToPlatformAlarmFilter
} from './report-alarm-filter.component';
import { AlarmFilterConfig as ReportAlarmFilterConfig } from '@shared/models/report-configuration.models';
import { AlarmFilterConfig } from '@shared/models/query/query.models';
import { AlarmSearchStatus, AlarmSeverity } from '@shared/models/alarm.models';
import { UserId } from '@shared/models/id/user-id';

describe('report-alarm-filter mapping', () => {

  const assigneeId = new UserId('2c6e6900-96b4-11ee-b9d1-0242ac120002');

  // task-6-brief.md's contract: a report value with a concrete assigneeId round-trips without loss.
  const briefFixture: ReportAlarmFilterConfig = {
    typeList: ['high temperature'],
    statusList: [AlarmSearchStatus.ACTIVE, AlarmSearchStatus.UNACK],
    severityList: [AlarmSeverity.CRITICAL, AlarmSeverity.MAJOR],
    searchPropagatedAlarms: true,
    assigneeId
  };

  it('round-trips a report value with a concrete assigneeId through platform and back without loss', () => {
    const platform = reportToPlatformAlarmFilter(briefFixture);

    expect(platform.typeList).toEqual(['high temperature']);
    expect(platform.statusList).toEqual([AlarmSearchStatus.ACTIVE, AlarmSearchStatus.UNACK]);
    expect(platform.severityList).toEqual([AlarmSeverity.CRITICAL, AlarmSeverity.MAJOR]);
    expect(platform.searchPropagatedAlarms).toBe(true);
    expect(platform.assigneeId).toBe(assigneeId);
    // the report shape has no "current user" sentinel, so a concrete assigneeId always maps to
    // "not assigned to current user" on the platform side.
    expect(platform.assignedToCurrentUser).toBe(false);

    expect(platformToReportAlarmFilter(platform)).toEqual(briefFixture);
  });

  it('maps typeList/statusList/severityList/searchPropagatedAlarms straight through with no assignee set', () => {
    const reportFilter: ReportAlarmFilterConfig = {
      typeList: ['low battery'],
      statusList: [AlarmSearchStatus.CLEARED],
      severityList: [AlarmSeverity.WARNING],
      searchPropagatedAlarms: false
    };

    const platform = reportToPlatformAlarmFilter(reportFilter);
    expect(platform).toEqual({
      typeList: ['low battery'],
      statusList: [AlarmSearchStatus.CLEARED],
      severityList: [AlarmSeverity.WARNING],
      searchPropagatedAlarms: false,
      assignedToCurrentUser: false
    });

    expect(platformToReportAlarmFilter(platform)).toEqual(reportFilter);
  });

  it('drops assignedToCurrentUser=true rather than persisting it into the report shape (the widget\'s own ' +
    'alarmFilterConfigFromFormValue rule: assignedToCurrentUser is derived from the currentUser sentinel, ' +
    'never stored as a UserId)', () => {
    // the literal shape tb-alarm-filter-config's own alarmFilterConfigFromFormValue() emits when a
    // user picks "assigned to me" in the widget UI (AlarmAssigneeOption.currentUser sentinel):
    // assignedToCurrentUser: true, assigneeId: null.
    const platformCurrentUser: AlarmFilterConfig = {
      statusList: [AlarmSearchStatus.ACTIVE],
      assignedToCurrentUser: true,
      assigneeId: null
    };

    const report = platformToReportAlarmFilter(platformCurrentUser);

    expect(report.assigneeId).toBeUndefined();
    expect('assignedToCurrentUser' in report).toBe(false);
    expect(report).toEqual({ statusList: [AlarmSearchStatus.ACTIVE] });
  });

  it('CONCERN: the "assigned to current user" platform state cannot round-trip - re-entering the resulting ' +
    'report value always produces assignedToCurrentUser=false, never true again', () => {
    const platformCurrentUser: AlarmFilterConfig = {
      assignedToCurrentUser: true,
      assigneeId: null
    };

    const report = platformToReportAlarmFilter(platformCurrentUser);
    const platformAgain = reportToPlatformAlarmFilter(report);

    expect(platformAgain.assignedToCurrentUser).toBe(false);
    expect(platformAgain.assigneeId).toBeUndefined();
  });

  it('assignedToCurrentUser=true takes priority over a simultaneously-set assigneeId, matching the widget\'s ' +
    'own updateAlarmConfigForm precedence', () => {
    const platformBoth: AlarmFilterConfig = {
      assignedToCurrentUser: true,
      assigneeId
    };

    const report = platformToReportAlarmFilter(platformBoth);

    expect(report.assigneeId).toBeUndefined();
  });

  it('treats an absent filter as fully optional in both directions', () => {
    expect(reportToPlatformAlarmFilter({})).toEqual({ assignedToCurrentUser: false });
    expect(platformToReportAlarmFilter({})).toEqual({});
    expect(reportToPlatformAlarmFilter(undefined)).toEqual({ assignedToCurrentUser: false });
    expect(platformToReportAlarmFilter(undefined)).toEqual({});
  });
});

describe('ReportAlarmFilterComponent', () => {

  const assigneeId = new UserId('2c6e6900-96b4-11ee-b9d1-0242ac120002');

  function newComponent(): ReportAlarmFilterComponent {
    const component = new ReportAlarmFilterComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('converts report -> platform on writeValue without notifying, then platform -> report on an inner control change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue({ statusList: [AlarmSearchStatus.ACTIVE], assigneeId });
    expect(onChange).not.toHaveBeenCalled();
    expect(component.alarmFilterFormGroup.value.alarmFilter.assigneeId).toBe(assigneeId);
    expect(component.alarmFilterFormGroup.value.alarmFilter.assignedToCurrentUser).toBe(false);

    const changedPlatformValue: AlarmFilterConfig = {
      statusList: [AlarmSearchStatus.CLEARED],
      assignedToCurrentUser: true,
      assigneeId: null
    };
    component.alarmFilterFormGroup.patchValue({ alarmFilter: changedPlatformValue });

    expect(onChange).toHaveBeenCalledWith({ statusList: [AlarmSearchStatus.CLEARED] });
  });

  it('writeValue tolerates an undefined value (new, unconfigured component)', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.alarmFilterFormGroup.value.alarmFilter).toEqual({ assignedToCurrentUser: false });
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.alarmFilterFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.alarmFilterFormGroup.disabled).toBe(false);
  });
});
