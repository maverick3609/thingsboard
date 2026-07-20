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
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';
import { ReportHistoryTableConfigResolver } from '@home/pages/report/report-history/report-history-table-config.resolver';
import { ReportHistoryComponent } from '@home/pages/report/report-history/report-history.component';

// Route for the report history page only (path 'history'). The parent 'reports' path mounting
// and the left-nav menu entry are Task 25's job (they need a MenuId that doesn't exist yet) -
// this module just has to be self-consistent, mirroring SchedulerRoutingModule.
export const reportRoutes: Routes = [
  {
    path: 'history',
    component: ReportHistoryComponent,
    data: {
      auth: [Authority.TENANT_ADMIN],
      title: 'report.reports',
      breadcrumb: {
        label: 'report.reports'
      }
    },
    resolve: {
      entitiesTableConfig: ReportHistoryTableConfigResolver
    }
  }
];

@NgModule({
  imports: [RouterModule.forChild(reportRoutes)],
  exports: [RouterModule],
  providers: [ReportHistoryTableConfigResolver]
})
export class ReportRoutingModule { }
