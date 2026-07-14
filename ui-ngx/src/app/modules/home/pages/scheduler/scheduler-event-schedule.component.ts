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

import { Component, forwardRef, OnDestroy } from '@angular/core';
import {
  ControlValueAccessor, FormBuilder, FormGroup, NG_VALIDATORS, NG_VALUE_ACCESSOR,
  ValidationErrors, Validator, Validators
} from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  SchedulerEventSchedule, SchedulerRepeat, SchedulerRepeatType,
  schedulerRepeatTypeTranslations, SchedulerTimeUnit
} from '@shared/models/scheduler-event.models';

@Component({
  selector: 'tb-scheduler-event-schedule',
  templateUrl: './scheduler-event-schedule.component.html',
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SchedulerEventScheduleComponent), multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => SchedulerEventScheduleComponent), multi: true}
  ],
  standalone: false
})
export class SchedulerEventScheduleComponent implements ControlValueAccessor, Validator, OnDestroy {

  scheduleFormGroup: FormGroup;
  repeatTypes = Object.values(SchedulerRepeatType);
  repeatTypeTranslations = schedulerRepeatTypeTranslations;
  timeUnits = Object.values(SchedulerTimeUnit);
  weekDays = [0, 1, 2, 3, 4, 5, 6]; // 0=Sunday (PE convention)
  weekDayLabels = ['scheduler.sunday', 'scheduler.monday', 'scheduler.tuesday', 'scheduler.wednesday',
    'scheduler.thursday', 'scheduler.friday', 'scheduler.saturday'];

  private propagateChange = (_v: any) => {};
  private valueChanges$: Subscription;

  constructor(private fb: FormBuilder) {
    this.scheduleFormGroup = this.fb.group({
      timezone: [null, Validators.required],
      startTime: [null, Validators.required],
      repeatEnabled: [false],
      repeat: this.fb.group({
        type: [SchedulerRepeatType.DAILY],
        endsOn: [null],
        repeatOn: [[1, 2, 3, 4, 5]],
        days: [1],
        weeks: [1],
        repeatInterval: [1],
        timeUnit: [SchedulerTimeUnit.HOURS]
      })
    });
    this.valueChanges$ = this.scheduleFormGroup.valueChanges.subscribe(() => this.updateModel());
  }

  ngOnDestroy(): void {
    this.valueChanges$.unsubscribe();
  }

  registerOnChange(fn: any): void { this.propagateChange = fn; }
  registerOnTouched(_fn: any): void {}

  writeValue(value: SchedulerEventSchedule | null): void {
    const repeat = value?.repeat;
    this.scheduleFormGroup.patchValue({
      timezone: value?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone,
      startTime: value?.startTime || Date.now() + 3600 * 1000,
      repeatEnabled: !!repeat,
      repeat: {
        type: repeat?.type || SchedulerRepeatType.DAILY,
        endsOn: repeat?.endsOn || null,
        repeatOn: repeat?.repeatOn || [1, 2, 3, 4, 5],
        days: repeat?.days || 1,
        weeks: repeat?.weeks || 1,
        repeatInterval: repeat?.repeatInterval || 1,
        timeUnit: repeat?.timeUnit || SchedulerTimeUnit.HOURS
      }
    }, {emitEvent: false});
  }

  validate(): ValidationErrors | null {
    if (!this.scheduleFormGroup.get('timezone').value || !this.scheduleFormGroup.get('startTime').value) {
      return {scheduleInvalid: true};
    }
    if (this.scheduleFormGroup.get('repeatEnabled').value) {
      const r = this.scheduleFormGroup.get('repeat').value;
      if (!r.endsOn) {
        return {scheduleInvalid: true};
      }
      if (r.type === SchedulerRepeatType.WEEKLY && (!r.repeatOn || !r.repeatOn.length)) {
        return {scheduleInvalid: true};
      }
    }
    return null;
  }

  get repeatType(): SchedulerRepeatType {
    return this.scheduleFormGroup.get('repeat').get('type').value;
  }

  isWeekDaySelected(day: number): boolean {
    return (this.scheduleFormGroup.get('repeat').get('repeatOn').value || []).includes(day);
  }

  toggleWeekDay(day: number): void {
    const control = this.scheduleFormGroup.get('repeat').get('repeatOn');
    const current: number[] = [...(control.value || [])];
    const idx = current.indexOf(day);
    if (idx >= 0) {
      current.splice(idx, 1);
    } else {
      current.push(day);
      current.sort();
    }
    control.setValue(current);
  }

  private updateModel(): void {
    const form = this.scheduleFormGroup.getRawValue();
    const schedule: SchedulerEventSchedule = {
      timezone: form.timezone,
      startTime: form.startTime
    };
    if (form.repeatEnabled) {
      const repeat: SchedulerRepeat = {type: form.repeat.type, endsOn: form.repeat.endsOn};
      switch (form.repeat.type) {
        case SchedulerRepeatType.WEEKLY: repeat.repeatOn = form.repeat.repeatOn; break;
        case SchedulerRepeatType.EVERY_N_DAYS: repeat.days = form.repeat.days; break;
        case SchedulerRepeatType.EVERY_N_WEEKS: repeat.weeks = form.repeat.weeks; break;
        case SchedulerRepeatType.TIMER:
          repeat.repeatInterval = form.repeat.repeatInterval;
          repeat.timeUnit = form.repeat.timeUnit;
          break;
      }
      schedule.repeat = repeat;
    }
    this.propagateChange(schedule);
  }
}
