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

// NOTE: originatorId is NOT here. The backend fires to the TOP-LEVEL bean field
// SchedulerEventInfo.originatorId (BaseSchedulerEventService.getOriginatorId =
// event.getOriginatorId() ?: event.getId()). No backend code reads
// configuration.originatorId. Configuration is msgType/msgBody/metadata only.
export interface SchedulerEventConfiguration {
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
