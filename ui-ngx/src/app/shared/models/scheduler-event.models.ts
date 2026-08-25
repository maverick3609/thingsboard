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

import { BaseData } from '@shared/models/base-data';
import { TenantId } from '@shared/models/id/tenant-id';
import { CustomerId } from '@shared/models/id/customer-id';
import { EntityId } from '@shared/models/id/entity-id';
import { SchedulerEventId } from '@shared/models/id/scheduler-event-id';
import { TranslateService } from '@ngx-translate/core';
import moment_ from 'moment';

const moment = moment_;

export enum SchedulerRepeatType {
  DAILY = 'DAILY',
  EVERY_N_DAYS = 'EVERY_N_DAYS',
  WEEKLY = 'WEEKLY',
  EVERY_N_WEEKS = 'EVERY_N_WEEKS',
  MONTHLY = 'MONTHLY',
  YEARLY = 'YEARLY',
  TIMER = 'TIMER'
}

export const schedulerRepeatTypeTranslations = new Map<SchedulerRepeatType, string>([
  [SchedulerRepeatType.DAILY, 'scheduler.daily'],
  [SchedulerRepeatType.EVERY_N_DAYS, 'scheduler.every-n-days'],
  [SchedulerRepeatType.WEEKLY, 'scheduler.weekly'],
  [SchedulerRepeatType.EVERY_N_WEEKS, 'scheduler.every-n-weeks'],
  [SchedulerRepeatType.MONTHLY, 'scheduler.monthly'],
  [SchedulerRepeatType.YEARLY, 'scheduler.yearly'],
  [SchedulerRepeatType.TIMER, 'scheduler.timer']
]);

export enum SchedulerTimeUnit {
  SECONDS = 'SECONDS',
  MINUTES = 'MINUTES',
  HOURS = 'HOURS'
}

export interface SchedulerRepeat {
  type: SchedulerRepeatType;
  endsOn: number;
  repeatOn?: number[];      // WEEKLY: 0=Sunday..6=Saturday
  days?: number;            // EVERY_N_DAYS
  weeks?: number;           // EVERY_N_WEEKS
  repeatInterval?: number;  // TIMER
  timeUnit?: SchedulerTimeUnit; // TIMER
}

export interface SchedulerEventSchedule {
  timezone: string;
  startTime: number;
  repeat?: SchedulerRepeat;
}

export interface SchedulerEventConfiguration {
  // TRANSIENT UI-only field: the per-type config form's originator picker writes it here,
  // and the dialog lifts it out to the top-level SchedulerEventInfo.originatorId bean field
  // (deleting it from configuration) before POST. The engine reads the top-level bean field;
  // the persisted configuration JSON never contains originatorId. See spec §2 "UI originator flow".
  originatorId?: EntityId;
  msgType?: string;
  msgBody?: any;
  metadata?: {[key: string]: string};
}

export interface SchedulerEventInfo extends BaseData<SchedulerEventId> {
  tenantId?: TenantId;
  customerId?: CustomerId;
  originatorId?: EntityId;   // top-level: the entity the event fires to (engine reads this)
  name: string;
  type: string;
  schedule: SchedulerEventSchedule;
  enabled: boolean;
  additionalInfo?: any;
}

export interface SchedulerEventWithCustomerInfo extends SchedulerEventInfo {
  customerTitle?: string;
  customerIsPublic?: boolean;
  timestamps?: number[];
}

export interface SchedulerEvent extends SchedulerEventInfo {
  configuration: SchedulerEventConfiguration;
}

// TIMER timeUnit -> i18n key (PE `uT`)
const timeUnitToI18n: {[unit: string]: string} = {
  HOURS: 'scheduler.every-hour',
  MINUTES: 'scheduler.every-minute',
  SECONDS: 'scheduler.every-second'
};

// day-of-week i18n keys (PE `OG`)
const dayOfWeekI18n = [
  'scheduler.sunday', 'scheduler.monday', 'scheduler.tuesday', 'scheduler.wednesday',
  'scheduler.thursday', 'scheduler.friday', 'scheduler.saturday'
];

// Human-readable summary of a schedule (PE `Bte`). Two callers with two different line separators:
// the calendar tooltip (scheduler-events.component.ts) renders HTML and passes '<br/>' plus the
// specific occurrence it is describing; the report Scheduling table renders plain text and lets
// `start` default to the schedule's own startTime.
export function schedulerEventScheduleText(schedule: SchedulerEventSchedule,
                                           translate: TranslateService,
                                           start?: moment_.Moment,
                                           separator: string = ', '): string {
  const m = start ?? moment(schedule.startTime);
  let s = '';
  if (schedule.repeat) {
    const r = schedule.repeat;
    s += m.local().format('hh:mma') + separator;
    s += translate.instant('scheduler.starting-from') + ' ' + m.local().format('MMM DD, YYYY') + ', ';
    if (r.type === SchedulerRepeatType.DAILY) {
      s += translate.instant('scheduler.daily') + ', ';
    } else if (r.type === SchedulerRepeatType.EVERY_N_DAYS) {
      s += translate.instant('scheduler.every-n-days-text', { days: r.days }) + ', ';
    } else if (r.type === SchedulerRepeatType.MONTHLY) {
      s += translate.instant('scheduler.monthly') + ', ';
    } else if (r.type === SchedulerRepeatType.EVERY_N_WEEKS) {
      s += translate.instant('scheduler.every-n-weeks-text', { weeks: r.weeks }) + ', ';
    } else if (r.type === SchedulerRepeatType.YEARLY) {
      s += translate.instant('scheduler.yearly') + ', ';
    } else if (r.type === SchedulerRepeatType.TIMER) {
      s += translate.instant(timeUnitToI18n[r.timeUnit], { count: r.repeatInterval }) + ', ';
    } else { // WEEKLY
      s += translate.instant('scheduler.weekly') + ' ' + translate.instant('scheduler.on') + ' ';
      (r.repeatOn || []).forEach((d: number) => { s += translate.instant(dayOfWeekI18n[d]) + ', '; });
    }
    s += translate.instant('scheduler.ending-on') + ' ' + moment(r.endsOn).local().format('MMM DD, YYYY');
  } else {
    s += m.local().format('hh:mma') + separator + m.local().format('MMM DD, YYYY');
  }
  return s;
}

export interface SchedulerEventConfigType {
  name: string;               // i18n key or literal shown in the type select
  originator?: boolean;       // config form shows originator picker
  msgType?: boolean;          // config form shows msgType input
  metadata?: boolean;         // config form shows metadata editor
  template?: boolean;         // reserved (PE custom config components)
}

// PE-identical shipped types (chunk-LQGPECC4.js); 'generateReport' is removed
// from this map for CUSTOMER_USER at dialog-open time.
export const schedulerEventConfigTypes: {[type: string]: SchedulerEventConfigType} = {
  generateReport: { name: 'scheduler.generate-report' },
  generateDashboardReport: { name: 'scheduler.generate-dashboard-report' },
  updateAttributes: { name: 'scheduler.update-attributes' },
  sendRpcRequest: { name: 'scheduler.send-rpc-request' },
  updateFirmware: { name: 'scheduler.update-firmware' },
  updateSoftware: { name: 'scheduler.update-software' }
};
