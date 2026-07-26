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

import { Component, forwardRef, Input, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { coerceBoolean } from '@shared/decorators/coercion';
import { isDefinedAndNotNull } from '@core/utils';
import {
  AggregationConfiguration,
  ReportInterval,
  ReportIntervalType,
  QuickTimeInterval as ReportQuickTimeInterval,
  TimeWindowConfiguration,
  TimeWindowHistory
} from '@shared/models/report-configuration.models';
import {
  Aggregation,
  FixedWindow,
  HistoryWindow,
  HistoryWindowType,
  Interval,
  QuickTimeInterval,
  Timewindow,
  TimewindowType
} from '@shared/models/time/time.models';
import { IntervalType } from '@shared/models/telemetry/telemetry.models';

// CVA shim for timewindow/TimeWindowConfiguration.java <-> the platform's tb-timewindow widget
// (shared/components/time/timewindow.component.ts), whose own CVA value is the FE-generic
// Timewindow (shared/models/time/time.models.ts) - a materially different shape from this repo's
// report wire model (c2-reuse-cva-notes.md's tb-timewindow section). Consumed by ALARM_TABLE's and
// TIME_SERIES_TABLE's panels (Task 11); DASHBOARD binds tb-timewindow directly with no shim
// (Task 14 - its `DashboardReportConfig.timewindow` field is untyped `any`, R1-era).
//
// reportToPlatformTimewindow()/platformToReportTimewindow() below are the two pure mapping
// functions, exported standalone (not private methods) so a mapping bug is directly unit-testable
// without the Angular CVA/DOM machinery. Field by field:
//  - history.historyType: the report's raw Java `int` (report-configuration.models.ts: "no
//    CE-side enum declared for it") <-> platform HistoryWindowType, mapped through an explicit
//    ordinal table (HISTORY_WINDOW_TYPE_BY_ORDINAL) rather than a bare numeric cast, so a future
//    reorder of HistoryWindowType in TB-core shows up as a diff here instead of silently
//    corrupting persisted report timewindows. Confirmed against the PE-derived default fixture
//    (c2-reuse-cva-notes.md): historyType 0 pairs with a timewindowMs-only window, i.e.
//    HistoryWindowType.LAST_INTERVAL.
//  - history.interval: the report's ReportInterval (a bare wire number-or-enum-name-string, per
//    Interval.java's custom Jackson serializer - see report-configuration.models.ts) <-> the
//    platform's identically-shaped Interval (`number | IntervalType`). ReportIntervalType's 5
//    members are a strict same-name/same-value subset of IntervalType's 6 (missing only CUSTOM),
//    so report->platform is total (reportIntervalToPlatform); the reverse
//    (platformIntervalToReport) drops IntervalType.CUSTOM rather than emit a bare 'CUSTOM' string
//    the report's Java deserializer explicitly rejects. Traced timeinterval.component.ts (the
//    tb-timeinterval picker tb-timewindow's history.interval editor delegates to): CUSTOM is a
//    UI-form-internal marker for "show the days/hours/mins/secs sub-form" and is never the value
//    actually propagated as the outer Interval (custom entries propagate as a plain
//    milliseconds number), so this branch is unreachable through tb-timewindow's real
//    writeValue/valueChanges flow - guarded defensively regardless.
//  - history.quickInterval / aggregation.type&limit / timezone: same member names+values (for
//    aggregation.type, literally the same AggregationType enum - report-configuration.models.ts
//    imports it straight from time.models.ts) on both sides, copied directly (quickInterval needs
//    a type-level cast only, since the report and platform QuickTimeInterval are separate, though
//    value-identical, TS string enums).
//  - history.timewindowMs / history.fixedTimewindow: identical shape on both sides, copied as-is.
//  - selectedTab (like C1's Font.sizeUnit) is FE-only: reportToPlatformTimewindow always sets
//    HISTORY (this shim always renders tb-timewindow with [historyOnly]="true");
//    platformToReportTimewindow drops it, along with every other FE-only Timewindow field
//    (realtime, hideAggregation, hideAggInterval, hideTimezone, allowedAggTypes,
//    hideSaveAsDefault, displayValue, displayTimezoneAbbr, aggregation.interval) -
//    TimeWindowConfiguration has no slot for any of them.
// null and absent are treated identically throughout (isDefinedAndNotNull guards, never a bare
// `!== undefined`), matching this repo's established CVA/Jackson-boundary convention (C1's ACC-1)
// that an object-shaped jsonb field being explicitly `null` on the wire and being omitted are
// equivalent - this also protects fixedTimewindow's nested property access from a stray
// `fixedTimewindow: null` on either side.

// timewindow/History.java's `historyType` wire values are the ordinal positions of the platform's
// HistoryWindowType enum - see the class-level comment above for how this was confirmed.
const HISTORY_WINDOW_TYPE_BY_ORDINAL: HistoryWindowType[] = [
  HistoryWindowType.LAST_INTERVAL, // 0
  HistoryWindowType.FIXED,         // 1
  HistoryWindowType.INTERVAL,      // 2
  HistoryWindowType.FOR_ALL_TIME   // 3
];

function reportIntervalToPlatform(interval: ReportInterval): Interval {
  return typeof interval === 'number' ? interval : (interval as unknown as IntervalType);
}

function platformIntervalToReport(interval: Interval): ReportInterval | undefined {
  if (typeof interval === 'number') {
    return interval;
  }
  if (interval === IntervalType.CUSTOM) {
    return undefined;
  }
  return interval as unknown as ReportIntervalType;
}

export function reportToPlatformTimewindow(tw: TimeWindowConfiguration): Timewindow {
  const platform: Timewindow = {
    selectedTab: TimewindowType.HISTORY
  };

  const history = tw?.history;
  if (isDefinedAndNotNull(history)) {
    const platformHistory: HistoryWindow = {};
    if (isDefinedAndNotNull(history.historyType)) {
      platformHistory.historyType = HISTORY_WINDOW_TYPE_BY_ORDINAL[history.historyType];
    }
    if (isDefinedAndNotNull(history.interval)) {
      platformHistory.interval = reportIntervalToPlatform(history.interval);
    }
    if (isDefinedAndNotNull(history.timewindowMs)) {
      platformHistory.timewindowMs = history.timewindowMs;
    }
    if (isDefinedAndNotNull(history.fixedTimewindow)) {
      platformHistory.fixedTimewindow = {
        startTimeMs: history.fixedTimewindow.startTimeMs,
        endTimeMs: history.fixedTimewindow.endTimeMs
      } as FixedWindow;
    }
    if (isDefinedAndNotNull(history.quickInterval)) {
      platformHistory.quickInterval = history.quickInterval as unknown as QuickTimeInterval;
    }
    platform.history = platformHistory;
  }

  const aggregation = tw?.aggregation;
  if (isDefinedAndNotNull(aggregation)) {
    const platformAggregation: Partial<Aggregation> = {};
    if (isDefinedAndNotNull(aggregation.type)) {
      platformAggregation.type = aggregation.type;
    }
    if (isDefinedAndNotNull(aggregation.limit)) {
      platformAggregation.limit = aggregation.limit;
    }
    platform.aggregation = platformAggregation as Aggregation;
  }

  if (isDefinedAndNotNull(tw?.timezone)) {
    platform.timezone = tw.timezone;
  }

  return platform;
}

export function platformToReportTimewindow(tw: Timewindow): TimeWindowConfiguration {
  const report: TimeWindowConfiguration = {};

  const history = tw?.history;
  if (isDefinedAndNotNull(history)) {
    const reportHistory: TimeWindowHistory = {};
    if (isDefinedAndNotNull(history.historyType)) {
      reportHistory.historyType = history.historyType as number;
    }
    if (isDefinedAndNotNull(history.interval)) {
      const interval = platformIntervalToReport(history.interval);
      if (interval !== undefined) {
        reportHistory.interval = interval;
      }
    }
    if (isDefinedAndNotNull(history.timewindowMs)) {
      reportHistory.timewindowMs = history.timewindowMs;
    }
    if (isDefinedAndNotNull(history.fixedTimewindow)) {
      reportHistory.fixedTimewindow = {
        startTimeMs: history.fixedTimewindow.startTimeMs,
        endTimeMs: history.fixedTimewindow.endTimeMs
      };
    }
    if (isDefinedAndNotNull(history.quickInterval)) {
      reportHistory.quickInterval = history.quickInterval as unknown as ReportQuickTimeInterval;
    }
    report.history = reportHistory;
  }

  const aggregation = tw?.aggregation;
  if (isDefinedAndNotNull(aggregation)) {
    const reportAggregation: AggregationConfiguration = {};
    if (isDefinedAndNotNull(aggregation.type)) {
      reportAggregation.type = aggregation.type;
    }
    if (isDefinedAndNotNull(aggregation.limit)) {
      reportAggregation.limit = aggregation.limit;
    }
    report.aggregation = reportAggregation;
  }

  if (isDefinedAndNotNull(tw?.timezone)) {
    report.timezone = tw.timezone;
  }

  return report;
}

// CVA for timewindow/TimeWindowConfiguration.java. Wraps a single tb-timewindow instance (always
// [historyOnly]="true" - reports have no realtime/streaming concept) behind the mapping functions
// above: writeValue() converts report->platform into the (single-control) form; the control's
// valueChanges converts platform->report before propagateChange. `aggregation`/`timezone` are
// passed straight through to tb-timewindow's own same-named inputs (both default false, matching
// tb-timewindow's own defaults) rather than hard-coded, since this shim's two consumers differ
// here: TIME_SERIES_TABLE needs the aggregation controls shown, ALARM_TABLE doesn't
// (c2-reuse-cva-notes.md: the PE timeSeriesTable default fixture carries an `aggregation`
// sub-object, the PE alarmTable default doesn't).
@Component({
    selector: 'tb-report-timewindow',
    templateUrl: './report-timewindow.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportTimewindowComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportTimewindowComponent implements ControlValueAccessor, OnInit, OnDestroy {

  timewindowFormGroup: UntypedFormGroup;

  disabled = false;

  @Input()
  @coerceBoolean()
  aggregation = false;

  @Input()
  @coerceBoolean()
  timezone = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: TimeWindowConfiguration) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.timewindowFormGroup = this.fb.group({
      timewindow: [null]
    });
    this.timewindowFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: { timewindow: Timewindow }) => this.propagateChange(platformToReportTimewindow(value.timewindow)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: TimeWindowConfiguration) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.timewindowFormGroup.disable({emitEvent: false});
    } else {
      this.timewindowFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: TimeWindowConfiguration): void {
    this.timewindowFormGroup.reset({
      timewindow: reportToPlatformTimewindow(value)
    }, {emitEvent: false});
  }
}
