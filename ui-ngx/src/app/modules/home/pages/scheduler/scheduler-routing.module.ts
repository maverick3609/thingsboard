// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';
import { MenuId } from '@core/services/menu.models';
import { SchedulerEventsTableConfigResolver } from '@home/pages/scheduler/scheduler-events-table-config.resolver';
import { SchedulerEventsComponent } from '@home/pages/scheduler/scheduler-events.component';

export const schedulerRoutes: Routes = [
  {
    path: 'scheduler',
    component: SchedulerEventsComponent,
    data: {
      auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
      title: 'scheduler.scheduler',
      breadcrumb: {
        menuId: MenuId.scheduler
      }
    },
    resolve: {
      entitiesTableConfig: SchedulerEventsTableConfigResolver
    }
  }
];

@NgModule({
  imports: [RouterModule.forChild(schedulerRoutes)],
  exports: [RouterModule],
  providers: [SchedulerEventsTableConfigResolver]
})
export class SchedulerRoutingModule { }
