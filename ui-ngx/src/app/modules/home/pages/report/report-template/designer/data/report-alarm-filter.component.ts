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

import { Component, forwardRef, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { isDefinedAndNotNull } from '@core/utils';
import { AlarmFilterConfig as ReportAlarmFilterConfig } from '@shared/models/report-configuration.models';
import { AlarmFilterConfig } from '@shared/models/query/query.models';

// CVA shim for AlarmFilterConfig.java <-> the platform's tb-alarm-filter-config widget
// (home/components/alarm/alarm-filter-config.component.ts), whose own CVA value is the FE-generic
// AlarmFilterConfig (shared/models/query/query.models.ts) - a superset of this repo's report wire
// model that additionally carries `assignedToCurrentUser` (plus AlarmFilter's startTs/endTs/
// timeWindow, which neither side ever populates through this widget: the widget's own
// alarmFilterConfigFromFormValue() never sets them, and the report model has no slot for them).
// Consumed by ALARM_TABLE's alarmSource panel (Task 11).
//
// reportToPlatformAlarmFilter()/platformToReportAlarmFilter() below are the two pure mapping
// functions, exported standalone (not private methods) so a mapping bug is directly unit-testable
// without the Angular CVA/CDK-overlay machinery. Field by field:
//  - typeList/statusList/severityList/searchPropagatedAlarms: identical shape+enum members on both
//    sides (report-configuration.models.ts's AlarmSearchStatus/AlarmSeverity ARE alarm.models.ts's
//    enums - same import, not a parallel copy), copied straight through, isDefinedAndNotNull-guarded
//    so a partial filter doesn't materialize explicit undefined keys on the other side (matches
//    C2 Task 5's report-timewindow convention).
//  - assigneeId <-> assignedToCurrentUser: the one real subtlety (c2-reuse-cva-notes.md's
//    tb-alarm-filter-config section). The platform CVA value pairs a concrete `assigneeId: UserId`
//    with a derived `assignedToCurrentUser: boolean` flag - the widget's own
//    alarmFilterConfigFromFormValue() (alarm-filter-config.component.ts:287-295) sets
//    `assignedToCurrentUser: formValue.assigneeId === AlarmAssigneeOption.currentUser`, where the
//    currentUser sentinel lives ONLY in the widget's own internal form control, never as a value
//    that flows through the outer AlarmFilterConfig itself. Its own updateAlarmConfigForm()
//    (writeValue's counterpart) treats assignedToCurrentUser as taking priority over any assigneeId
//    (`assignedToCurrentUser ? currentUser-sentinel : (assigneeId || noAssignee)`); this shim
//    mirrors that same precedence for a hypothetical caller that sets both.
//    The report model (report-configuration.models.ts) is FROZEN with only `assigneeId?: UserId` -
//    there is no sentinel slot for "assigned to current user", and AlarmAssigneeOption.currentUser
//    (a bare string) isn't a valid UserId, so it can't be fabricated into one. Consequently:
//     - reportToPlatformAlarmFilter is total: a concrete assigneeId always maps straight through,
//       and assignedToCurrentUser is always explicitly false - there is no report-side signal that
//       could ever justify true.
//     - platformToReportAlarmFilter drops assignedToCurrentUser entirely (never persisted into the
//       report shape, whatever its value) and only keeps assigneeId when assignedToCurrentUser is
//       falsy - a concrete assignee round-trips losslessly, but the "assigned to me" platform state
//       is NOT representable: converting it to a report value and back always yields
//       assignedToCurrentUser: false / no assigneeId, never the original "current user" intent.
//       KNOWN CONCERN (task-6-report.md): an ALARM_TABLE authored with "assigned to me" active
//       loses that intent to "no assignee" on save; there is no lossless fix without widening the
//       frozen report model.
export function reportToPlatformAlarmFilter(filter?: ReportAlarmFilterConfig): AlarmFilterConfig {
  const platform: AlarmFilterConfig = {
    assignedToCurrentUser: false
  };
  if (isDefinedAndNotNull(filter?.typeList)) {
    platform.typeList = filter.typeList;
  }
  if (isDefinedAndNotNull(filter?.statusList)) {
    platform.statusList = filter.statusList;
  }
  if (isDefinedAndNotNull(filter?.severityList)) {
    platform.severityList = filter.severityList;
  }
  if (isDefinedAndNotNull(filter?.searchPropagatedAlarms)) {
    platform.searchPropagatedAlarms = filter.searchPropagatedAlarms;
  }
  if (isDefinedAndNotNull(filter?.assigneeId)) {
    platform.assigneeId = filter.assigneeId;
  }
  return platform;
}

export function platformToReportAlarmFilter(filter?: AlarmFilterConfig): ReportAlarmFilterConfig {
  const report: ReportAlarmFilterConfig = {};
  if (isDefinedAndNotNull(filter?.typeList)) {
    report.typeList = filter.typeList;
  }
  if (isDefinedAndNotNull(filter?.statusList)) {
    report.statusList = filter.statusList;
  }
  if (isDefinedAndNotNull(filter?.severityList)) {
    report.severityList = filter.severityList;
  }
  if (isDefinedAndNotNull(filter?.searchPropagatedAlarms)) {
    report.searchPropagatedAlarms = filter.searchPropagatedAlarms;
  }
  if (!filter?.assignedToCurrentUser && isDefinedAndNotNull(filter?.assigneeId)) {
    report.assigneeId = filter.assigneeId;
  }
  return report;
}

// CVA for AlarmFilterConfig.java. Wraps a single tb-alarm-filter-config instance behind the
// mapping functions above: writeValue() converts report->platform into the (single-control) form;
// the control's valueChanges converts platform->report before propagateChange.
@Component({
    selector: 'tb-report-alarm-filter',
    templateUrl: './report-alarm-filter.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportAlarmFilterComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportAlarmFilterComponent implements ControlValueAccessor, OnInit, OnDestroy {

  alarmFilterFormGroup: UntypedFormGroup;

  disabled = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportAlarmFilterConfig) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.alarmFilterFormGroup = this.fb.group({
      alarmFilter: [null]
    });
    this.alarmFilterFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: { alarmFilter: AlarmFilterConfig }) =>
      this.propagateChange(platformToReportAlarmFilter(value.alarmFilter)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportAlarmFilterConfig) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.alarmFilterFormGroup.disable({emitEvent: false});
    } else {
      this.alarmFilterFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: ReportAlarmFilterConfig): void {
    this.alarmFilterFormGroup.reset({
      alarmFilter: reportToPlatformAlarmFilter(value)
    }, {emitEvent: false});
  }
}
