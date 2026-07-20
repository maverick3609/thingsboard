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
import { MenuId } from '@core/services/menu.models';
import { ReportHistoryTableConfigResolver } from '@home/pages/report/report-history/report-history-table-config.resolver';
import { ReportHistoryComponent } from '@home/pages/report/report-history/report-history.component';
import { ReportTemplatesTableConfigResolver } from '@home/pages/report/report-template/report-templates-table-config.resolver';
import { ReportTemplatesComponent } from '@home/pages/report/report-template/report-templates.component';

// Routes for the report history page ('history') and the report templates page ('templates'),
// nested under a parent 'reports' path (mirrors OtaUpdateRoutingModule's parent+children shape)
// so that spreading this const into FeaturesRoutingModule's children lands the pages at
// '/features/reports/history' and '/features/reports/templates'.
export const reportRoutes: Routes = [
  {
    path: 'reports',
    data: {
      auth: [Authority.TENANT_ADMIN],
      breadcrumb: {
        menuId: MenuId.reports
      }
    },
    children: [
      {
        path: 'history',
        component: ReportHistoryComponent,
        data: {
          auth: [Authority.TENANT_ADMIN],
          title: 'report.reports',
          breadcrumb: {
            menuId: MenuId.report_history
          }
        },
        resolve: {
          entitiesTableConfig: ReportHistoryTableConfigResolver
        }
      },
      {
        path: 'templates',
        component: ReportTemplatesComponent,
        data: {
          auth: [Authority.TENANT_ADMIN],
          title: 'report.report-templates',
          breadcrumb: {
            menuId: MenuId.report_templates
          }
        },
        resolve: {
          entitiesTableConfig: ReportTemplatesTableConfigResolver
        }
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(reportRoutes)],
  exports: [RouterModule],
  providers: [ReportHistoryTableConfigResolver, ReportTemplatesTableConfigResolver]
})
export class ReportRoutingModule { }
