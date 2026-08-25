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
import { RouterTabsComponent } from '@home/components/router-tabs.component';
import { ReportHistoryTableConfigResolver } from '@home/pages/report/report-history/report-history-table-config.resolver';
import { ReportHistoryComponent } from '@home/pages/report/report-history/report-history.component';
import {
  ReportSchedulingTableConfigResolver
} from '@home/pages/report/report-scheduling/report-scheduling-table-config.resolver';
import { ReportSchedulingComponent } from '@home/pages/report/report-scheduling/report-scheduling.component';
import { ReportTemplatesTableConfigResolver } from '@home/pages/report/report-template/report-templates-table-config.resolver';
import { ReportTemplatesComponent } from '@home/pages/report/report-template/report-templates.component';
import { ReportTemplateEditorComponent } from '@home/pages/report/report-template/designer/report-template-editor.component';

// Top-level '/reporting' section (promoted out of '/features' on 2026-08-24 - it is its own sidebar
// entry now, not a Features page). RouterTabsComponent renders the Templates / Scheduling / Reports
// tab strip in-page, picking the tabs out of the menu map (menu.models.ts's MenuId.reports group),
// the same way alarms_center does.
//
// 'templates' is itself further nested (table at '', full-page designer at 'new'/':reportTemplateId')
// rather than a single leaf route, mirroring widget-library-routing.module.ts's widget-types/
// widgets-bundles "table + detail" shape: a componentless grouping route contributes no router-outlet
// of its own, so its children still render straight into the parent's RouterTabsComponent outlet -
// keeping the tab strip working for the table, while letting the designer routes opt out of it via
// `hideTabs: true` (router-tabs.component.html reads that off the deepest activated route's own data).
export const reportRoutes: Routes = [
  {
    path: 'reporting',
    component: RouterTabsComponent,
    data: {
      auth: [Authority.TENANT_ADMIN],
      breadcrumb: {
        menuId: MenuId.reports
      }
    },
    children: [
      {
        // The 'Reporting' menu link points at '/reporting', which has no page of its own - redirect
        // to the templates tab (auth.guard consumes data.redirectTo). Without this the landing route
        // has no active tab and renders blank.
        path: '',
        children: [],
        data: {
          auth: [Authority.TENANT_ADMIN],
          redirectTo: '/reporting/templates'
        }
      },
      {
        path: 'templates',
        data: {
          breadcrumb: {
            menuId: MenuId.report_templates
          }
        },
        children: [
          {
            path: '',
            component: ReportTemplatesComponent,
            data: {
              auth: [Authority.TENANT_ADMIN],
              title: 'report.report-templates',
              isPage: true
            },
            resolve: {
              entitiesTableConfig: ReportTemplatesTableConfigResolver
            }
          },
          {
            path: 'new',
            component: ReportTemplateEditorComponent,
            data: {
              auth: [Authority.TENANT_ADMIN],
              title: 'report.add-report-template',
              breadcrumb: {
                label: 'report.add-report-template',
                icon: 'description'
              },
              hideTabs: true
            }
          },
          {
            path: ':reportTemplateId',
            component: ReportTemplateEditorComponent,
            data: {
              auth: [Authority.TENANT_ADMIN],
              title: 'report.edit-report-template',
              breadcrumb: {
                label: 'report.edit-report-template',
                icon: 'description'
              },
              hideTabs: true
            }
          }
        ]
      },
      {
        path: 'scheduling',
        component: ReportSchedulingComponent,
        data: {
          auth: [Authority.TENANT_ADMIN],
          title: 'report.scheduling',
          isPage: true,
          breadcrumb: {
            menuId: MenuId.report_scheduling
          }
        },
        resolve: {
          entitiesTableConfig: ReportSchedulingTableConfigResolver
        }
      },
      {
        path: 'reports',
        component: ReportHistoryComponent,
        data: {
          auth: [Authority.TENANT_ADMIN],
          title: 'report.reports',
          isPage: true,
          breadcrumb: {
            menuId: MenuId.report_history
          }
        },
        resolve: {
          entitiesTableConfig: ReportHistoryTableConfigResolver
        }
      },
      {
        // Pre-2026-08-24 deep link into the section's generated-reports list.
        path: 'history',
        redirectTo: 'reports'
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(reportRoutes)],
  exports: [RouterModule],
  providers: [
    ReportHistoryTableConfigResolver,
    ReportSchedulingTableConfigResolver,
    ReportTemplatesTableConfigResolver
  ]
})
export class ReportRoutingModule { }
