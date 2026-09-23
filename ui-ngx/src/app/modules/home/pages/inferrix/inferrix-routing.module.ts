// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Authority } from '@shared/models/authority.enum';
import { MenuId } from '@core/services/menu.models';
import { EntitiesTableComponent } from '@home/components/entity/entities-table.component';
import { EntityDetailsPageComponent } from '@home/components/entity/entity-details-page.component';
import { ConfirmOnExitGuard } from '@core/guards/confirm-on-exit.guard';
import { entityDetailsPageBreadcrumbLabelFunction } from '@home/pages/home-pages.models';
import { BreadCrumbConfig } from '@shared/components/breadcrumb';
import { ControllersTableConfigResolver } from '@home/pages/inferrix/controllers-table-config.resolver';
import { DiscoveredControllersTableConfigResolver }
  from '@home/pages/inferrix/discovered-controllers-table-config.resolver';
import { GatewaysTableConfigResolver } from '@home/pages/inferrix/gateways-table-config.resolver';
import { PendingGatewaysTableConfigResolver }
  from '@home/pages/inferrix/pending-gateways-table-config.resolver';

/**
 * Replaces ThingsBoard's own Gateways page in place.
 *
 * Mounted by `entities-routing.module.ts` at the same `/entities/gateways` path the stock page
 * used, and `MenuId.gateways` is left pointing at it — so the menu entry, the breadcrumb and every
 * existing link keep working, and there is no second Gateways item to explain. The stock page was a
 * system dashboard; this is an entities table over real devices.
 */
export const inferrixGatewayRoutes: Routes = [
  {
    path: 'gateways',
    data: {
      auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
      breadcrumb: {
        menuId: MenuId.gateways
      }
    },
    children: [
      {
        path: '',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.gateway.gateways'
        },
        resolve: {
          entitiesTableConfig: GatewaysTableConfigResolver
        }
      },
      {
        // Before the :entityId route, or "pending" is looked up as a device id.
        path: 'pending',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN],
          title: 'inferrix.gateway.pending',
          breadcrumb: {
            label: 'inferrix.gateway.pending',
            icon: 'pending_actions'
          }
        },
        resolve: {
          entitiesTableConfig: PendingGatewaysTableConfigResolver
        }
      },
      {
        path: ':entityId',
        component: EntityDetailsPageComponent,
        canDeactivate: [ConfirmOnExitGuard],
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.gateway.gateway',
          breadcrumb: {
            labelFunction: entityDetailsPageBreadcrumbLabelFunction,
            icon: 'lan'
          } as BreadCrumbConfig<EntityDetailsPageComponent>
        },
        resolve: {
          entitiesTableConfig: GatewaysTableConfigResolver
        }
      }
    ]
  }
];

export const inferrixRoutes: Routes = [
  {
    path: 'io-controllers',
    data: {
      auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER, Authority.SYS_ADMIN],
      breadcrumb: {
        menuId: MenuId.controllers
      }
    },
    children: [
      {
        path: '',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.controllers'
        },
        resolve: {
          entitiesTableConfig: ControllersTableConfigResolver
        }
      },
      {
        // Before the :entityId route, or an unadopted-controller list would be looked up as a
        // device id.
        path: 'discovered',
        component: EntitiesTableComponent,
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.SYS_ADMIN],
          title: 'inferrix.discovered-controllers',
          breadcrumb: {
            label: 'inferrix.discovered-controllers',
            icon: 'wifi_tethering'
          }
        },
        resolve: {
          entitiesTableConfig: DiscoveredControllersTableConfigResolver
        }
      },
      {
        path: ':entityId',
        component: EntityDetailsPageComponent,
        canDeactivate: [ConfirmOnExitGuard],
        data: {
          auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER],
          title: 'inferrix.controller',
          breadcrumb: {
            labelFunction: entityDetailsPageBreadcrumbLabelFunction,
            icon: 'developer_board'
          } as BreadCrumbConfig<EntityDetailsPageComponent>
        },
        resolve: {
          entitiesTableConfig: ControllersTableConfigResolver
        }
      }
    ]
  }
];

@NgModule({
  imports: [RouterModule.forChild(inferrixRoutes)],
  exports: [RouterModule],
  providers: [
    ControllersTableConfigResolver,
    DiscoveredControllersTableConfigResolver,
    GatewaysTableConfigResolver,
    PendingGatewaysTableConfigResolver
  ]
})
export class InferrixRoutingModule { }
