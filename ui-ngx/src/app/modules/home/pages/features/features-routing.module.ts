// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';
import { NgModule } from '@angular/core';
import { vcRoutes } from '@home/pages/vc/vc-routing.module';
import { schedulerRoutes } from '@home/pages/scheduler/scheduler-routing.module';
import { MenuId } from '@core/services/menu.models';

const routes: Routes = [
  {
    path: 'features',
    data: {
      auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
      breadcrumb: {
        menuId: MenuId.features
      }
    },
    children: [
      {
        path: '',
        children: [],
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          redirectTo: '/features/vc'
        }
      },
      ...vcRoutes,
      ...schedulerRoutes
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class FeaturesRoutingModule { }
