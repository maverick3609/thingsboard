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

import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { FullCalendarModule } from '@fullcalendar/angular';
import { SchedulerRoutingModule } from '@home/pages/scheduler/scheduler-routing.module';
import { SchedulerEventDialogComponent } from '@home/pages/scheduler/scheduler-event-dialog.component';
import { SchedulerEventScheduleComponent } from '@home/pages/scheduler/scheduler-event-schedule.component';
import { SchedulerEventConfigComponent } from '@home/pages/scheduler/scheduler-event-config.component';
import { SchedulerEventsComponent } from '@home/pages/scheduler/scheduler-events.component';

@NgModule({
  declarations: [
    SchedulerEventDialogComponent,
    SchedulerEventScheduleComponent,
    SchedulerEventConfigComponent,
    SchedulerEventsComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    FullCalendarModule,
    SchedulerRoutingModule
  ]
})
export class SchedulerModule { }
