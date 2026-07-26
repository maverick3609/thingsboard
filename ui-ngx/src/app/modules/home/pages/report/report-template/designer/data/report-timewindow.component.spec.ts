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

// Plain instantiation (no TestBed), mirroring report-divider-config.component.spec.ts /
// report-insets.component.spec.ts: the CVA plumbing only touches the injected UntypedFormBuilder
// and the reportToPlatformTimewindow()/platformToReportTimewindow() pure functions, never the DOM,
// so this proves the writeValue -> mapping -> propagateChange wiring - and the mapping functions'
// own round-trip correctness (task-5-brief.md's contract) - without compiling the tb-timewindow
// Material template.
import { UntypedFormBuilder } from '@angular/forms';
import {
  platformToReportTimewindow,
  ReportTimewindowComponent,
  reportToPlatformTimewindow
} from './report-timewindow.component';
import {
  QuickTimeInterval as ReportQuickTimeInterval,
  ReportIntervalType,
  TimeWindowConfiguration
} from '@shared/models/report-configuration.models';
import {
  AggregationType,
  HistoryWindowType,
  QuickTimeInterval,
  Timewindow,
  TimewindowType
} from '@shared/models/time/time.models';
import { IntervalType } from '@shared/models/telemetry/telemetry.models';

describe('report-timewindow mapping', () => {

  // task-5-brief.md Step 1's exact fixture.
  const briefFixture: TimeWindowConfiguration = {
    history: { historyType: 0, timewindowMs: 3600000 },
    aggregation: { type: AggregationType.AVG, limit: 200 },
    timezone: 'UTC'
  };

  it('round-trips the brief fixture through platform and back to report shape, historyType surviving the enum<->int hop', () => {
    const platformTw = reportToPlatformTimewindow(briefFixture);

    expect(platformTw.history.historyType).toBe(HistoryWindowType.LAST_INTERVAL);
    expect(platformTw.selectedTab).toBe(TimewindowType.HISTORY);
    expect(platformTw.history.timewindowMs).toBe(3600000);
    expect(platformTw.aggregation).toEqual({ type: AggregationType.AVG, limit: 200 });
    expect(platformTw.timezone).toBe('UTC');

    const roundTripped = platformToReportTimewindow(platformTw);
    expect(roundTripped).toEqual(briefFixture);
  });

  it('maps every HistoryWindowType member both ways without loss', () => {
    const members: Array<[number, HistoryWindowType]> = [
      [0, HistoryWindowType.LAST_INTERVAL],
      [1, HistoryWindowType.FIXED],
      [2, HistoryWindowType.INTERVAL],
      [3, HistoryWindowType.FOR_ALL_TIME]
    ];
    for (const [reportInt, platformMember] of members) {
      const platform = reportToPlatformTimewindow({ history: { historyType: reportInt } });
      expect(platform.history.historyType).toBe(platformMember);

      const back = platformToReportTimewindow({ history: { historyType: platformMember } });
      expect(back.history.historyType).toBe(reportInt);
    }
  });

  it('round-trips a numeric (millisecond) interval', () => {
    const reportTw: TimeWindowConfiguration = { history: { historyType: 0, interval: 60000 } };

    const platformTw = reportToPlatformTimewindow(reportTw);
    expect(platformTw.history.interval).toBe(60000);

    expect(platformToReportTimewindow(platformTw)).toEqual(reportTw);
  });

  it('round-trips a named ReportIntervalType interval together with a FIXED history window', () => {
    const reportTw: TimeWindowConfiguration = {
      history: {
        historyType: 1,
        interval: ReportIntervalType.WEEK,
        fixedTimewindow: { startTimeMs: 1000, endTimeMs: 2000 }
      }
    };

    const platformTw = reportToPlatformTimewindow(reportTw);
    expect(platformTw.history.interval).toBe(IntervalType.WEEK);
    expect(platformTw.history.fixedTimewindow).toEqual({ startTimeMs: 1000, endTimeMs: 2000 });

    expect(platformToReportTimewindow(platformTw)).toEqual(reportTw);
  });

  it('round-trips a partial fixedTimewindow (only startTimeMs set) without materializing an undefined endTimeMs key', () => {
    const reportTw: TimeWindowConfiguration = {
      history: { historyType: 1, fixedTimewindow: { startTimeMs: 123 } }
    };

    const platformTw = reportToPlatformTimewindow(reportTw);
    expect(platformTw.history.fixedTimewindow).toEqual(jasmine.objectContaining({ startTimeMs: 123 }));
    expect('endTimeMs' in platformTw.history.fixedTimewindow).toBe(false);

    const roundTripped = platformToReportTimewindow(platformTw);
    expect(roundTripped).toEqual(reportTw);
    expect('endTimeMs' in roundTripped.history.fixedTimewindow).toBe(false);
  });

  it('round-trips a quickInterval (HistoryWindowType.INTERVAL) history window', () => {
    const reportTw: TimeWindowConfiguration = {
      history: { historyType: 2, quickInterval: ReportQuickTimeInterval.CURRENT_DAY }
    };

    const platformTw = reportToPlatformTimewindow(reportTw);
    expect(platformTw.history.quickInterval).toBe(QuickTimeInterval.CURRENT_DAY);

    expect(platformToReportTimewindow(platformTw)).toEqual(reportTw);
  });

  it('drops the platform-only IntervalType.CUSTOM interval rather than emit an invalid report value', () => {
    const platformTw: Timewindow = { history: { interval: IntervalType.CUSTOM } };

    const reportTw = platformToReportTimewindow(platformTw);

    expect(reportTw.history.interval).toBeUndefined();
  });

  it('drops FE-only Timewindow fields (selectedTab, realtime, hideAggregation, ...) that TimeWindowConfiguration has no slot for', () => {
    const platformTw: Timewindow = {
      selectedTab: TimewindowType.HISTORY,
      realtime: {},
      hideAggregation: true,
      history: { historyType: HistoryWindowType.LAST_INTERVAL, timewindowMs: 3600000 }
    };

    const reportTw = platformToReportTimewindow(platformTw);

    expect(reportTw).toEqual({ history: { historyType: 0, timewindowMs: 3600000 } });
  });

  it('treats an absent history/aggregation/timezone as fully optional in both directions', () => {
    expect(reportToPlatformTimewindow({})).toEqual({ selectedTab: TimewindowType.HISTORY });
    expect(platformToReportTimewindow({})).toEqual({});
  });
});

describe('ReportTimewindowComponent', () => {

  function newComponent(): ReportTimewindowComponent {
    const component = new ReportTimewindowComponent(new UntypedFormBuilder());
    component.ngOnInit();
    return component;
  }

  it('converts report -> platform on writeValue without notifying, then platform -> report on an inner control change', () => {
    const component = newComponent();
    const onChange = jasmine.createSpy('onChange');
    component.registerOnChange(onChange);

    component.writeValue({ history: { historyType: 0, timewindowMs: 3600000 }, timezone: 'UTC' });
    expect(onChange).not.toHaveBeenCalled();
    expect(component.timewindowFormGroup.value.timewindow.history.historyType).toBe(HistoryWindowType.LAST_INTERVAL);
    expect(component.timewindowFormGroup.value.timewindow.selectedTab).toBe(TimewindowType.HISTORY);

    const changedPlatformValue: Timewindow = {
      selectedTab: TimewindowType.HISTORY,
      history: { historyType: HistoryWindowType.FOR_ALL_TIME }
    };
    component.timewindowFormGroup.patchValue({ timewindow: changedPlatformValue });

    expect(onChange).toHaveBeenCalledWith({ history: { historyType: 3 } });
  });

  it('writeValue tolerates an undefined value (new, unconfigured component)', () => {
    const component = newComponent();

    component.writeValue(undefined);

    expect(component.timewindowFormGroup.value.timewindow.selectedTab).toBe(TimewindowType.HISTORY);
  });

  it('passes aggregation/timezone Inputs straight through as tb-timewindow bindings', () => {
    const component = newComponent();

    expect(component.aggregation).toBe(false);
    expect(component.timezone).toBe(false);

    component.aggregation = true;
    component.timezone = true;

    expect(component.aggregation).toBe(true);
    expect(component.timezone).toBe(true);
  });

  it('disables and re-enables the underlying form via setDisabledState', () => {
    const component = newComponent();

    component.setDisabledState(true);
    expect(component.timewindowFormGroup.disabled).toBe(true);

    component.setDisabledState(false);
    expect(component.timewindowFormGroup.disabled).toBe(false);
  });
});
