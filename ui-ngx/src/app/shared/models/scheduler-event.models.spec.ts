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

// Plain instantiation (no TestBed): schedulerEventScheduleText is a pure function over a schedule
// plus a TranslateService, so a stub that echoes the key it was asked for is enough to assert which
// branch ran and where the separator lands.
import { TranslateService } from '@ngx-translate/core';
import moment from 'moment';
import {
  SchedulerEventSchedule,
  SchedulerRepeatType,
  SchedulerTimeUnit,
  schedulerEventScheduleText,
  schedulerEventStatus,
  SchedulerEventStatus
} from '@shared/models/scheduler-event.models';

const translate = {
  instant: (key: string) => key
} as TranslateService;

// Fixed instant so the formatted time/date assertions below are stable regardless of when the suite
// runs; formatting is .local(), so the expectations are derived the same way rather than hardcoded.
const startTime = moment('2026-03-04T09:30:00Z').valueOf();
const startTimeText = moment(startTime).local().format('hh:mma');
const startDateText = moment(startTime).local().format('MMM DD, YYYY');

describe('schedulerEventScheduleText', () => {

  it('renders a one-off schedule as time + date joined by the separator', () => {
    const schedule: SchedulerEventSchedule = {timezone: 'UTC', startTime};
    expect(schedulerEventScheduleText(schedule, translate))
      .toBe(`${startTimeText}, ${startDateText}`);
    expect(schedulerEventScheduleText(schedule, translate, undefined, '<br/>'))
      .toBe(`${startTimeText}<br/>${startDateText}`);
  });

  it('names the repeat type for a simple recurrence and always ends with the end date', () => {
    const endsOn = moment('2026-06-04T09:30:00Z').valueOf();
    const text = schedulerEventScheduleText(
      {timezone: 'UTC', startTime, repeat: {type: SchedulerRepeatType.DAILY, endsOn}}, translate);
    expect(text).toContain('scheduler.starting-from');
    expect(text).toContain('scheduler.daily');
    expect(text).toContain(`scheduler.ending-on ${moment(endsOn).local().format('MMM DD, YYYY')}`);
  });

  it('lists every selected day for a weekly recurrence', () => {
    const text = schedulerEventScheduleText({
      timezone: 'UTC',
      startTime,
      repeat: {type: SchedulerRepeatType.WEEKLY, endsOn: startTime, repeatOn: [1, 5]}
    }, translate);
    expect(text).toContain('scheduler.weekly scheduler.on');
    expect(text).toContain('scheduler.monday');
    expect(text).toContain('scheduler.friday');
    expect(text).not.toContain('scheduler.sunday');
  });

  it('uses the time-unit key for a TIMER recurrence', () => {
    const text = schedulerEventScheduleText({
      timezone: 'UTC',
      startTime,
      repeat: {
        type: SchedulerRepeatType.TIMER,
        endsOn: startTime,
        repeatInterval: 5,
        timeUnit: SchedulerTimeUnit.MINUTES
      }
    }, translate);
    expect(text).toContain('scheduler.every-minute');
  });

  it('falls back to the schedule start when no occurrence is passed', () => {
    const occurrence = moment(startTime).add(3, 'days');
    const text = schedulerEventScheduleText({timezone: 'UTC', startTime}, translate, occurrence);
    expect(text).toBe(`${occurrence.local().format('hh:mma')}, ${occurrence.local().format('MMM DD, YYYY')}`);
  });
});

describe('schedulerEventStatus', () => {

  const now = 1_700_000_000_000;
  const repeating = (endsOn: number): SchedulerEventSchedule => ({
    timezone: 'UTC',
    startTime: now - 86_400_000,
    repeat: {type: SchedulerRepeatType.DAILY, endsOn}
  });

  it('reports a live repeating event as active', () => {
    expect(schedulerEventStatus(repeating(now + 86_400_000), true, now)).toBe(SchedulerEventStatus.ACTIVE);
  });

  it('reports a repeating event past its end date as expired even while enabled', () => {
    expect(schedulerEventStatus(repeating(now - 1), true, now)).toBe(SchedulerEventStatus.EXPIRED);
  });

  it('reports a live but switched-off event as disabled', () => {
    expect(schedulerEventStatus(repeating(now + 86_400_000), false, now)).toBe(SchedulerEventStatus.DISABLED);
  });

  it('treats a one-shot whose start time has passed as expired', () => {
    expect(schedulerEventStatus({timezone: 'UTC', startTime: now - 1}, true, now)).toBe(SchedulerEventStatus.EXPIRED);
  });

  it('treats a one-shot still ahead of us as active', () => {
    expect(schedulerEventStatus({timezone: 'UTC', startTime: now + 1}, true, now)).toBe(SchedulerEventStatus.ACTIVE);
  });
});
