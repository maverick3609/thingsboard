// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
