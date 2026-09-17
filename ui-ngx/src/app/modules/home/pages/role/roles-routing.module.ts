// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { EntitiesTableComponent } from '@home/components/entity/entities-table.component';
import { Authority } from '@shared/models/authority.enum';
import { MenuId } from '@core/services/menu.models';
import { RolesTableConfigResolver } from '@home/pages/role/roles-table-config.resolver';

export const rolesRoutes: Routes = [
  {
    path: 'roles',
    component: EntitiesTableComponent,
    data: {
      auth: [Authority.TENANT_ADMIN],
      title: 'role.roles',
      breadcrumb: {
        menuId: MenuId.roles
      }
    },
    resolve: {
      entitiesTableConfig: RolesTableConfigResolver
    }
  }
];

@NgModule({
  providers: [
    RolesTableConfigResolver
  ],
  imports: [RouterModule.forChild(rolesRoutes)],
  exports: [RouterModule]
})
export class RolesRoutingModule { }
