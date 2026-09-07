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
import { ControllersComponent } from '@home/pages/inferrix/controllers.component';
import { DiscoveredControllersComponent } from '@home/pages/inferrix/discovered-controllers.component';
import { ControllerComponent } from '@home/pages/inferrix/controller/controller.component';

export const inferrixRoutes: Routes = [
  {
    path: 'controllers',
    data: {
      auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER, Authority.SYS_ADMIN],
      breadcrumb: {
        menuId: MenuId.controllers
      }
    },
    children: [
      {
        path: '',
        component: ControllersComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.controllers'
        }
      },
      {
        // Before the :entityId route, or an unadopted-controller list would be looked up as a
        // device id.
        path: 'discovered',
        component: DiscoveredControllersComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.SYS_ADMIN],
          title: 'inferrix.discovered-controllers',
          breadcrumb: {
            label: 'inferrix.discovered-controllers',
            icon: 'wifi_tethering'
          }
        }
      },
      {
        path: ':entityId',
        component: ControllerComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.controller',
          breadcrumb: {
            label: 'inferrix.controller',
            icon: 'developer_board'
          }
        }
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(inferrixRoutes)],
  exports: [RouterModule]
})
export class InferrixRoutingModule { }
