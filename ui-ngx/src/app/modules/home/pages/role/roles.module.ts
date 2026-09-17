// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { RolesRoutingModule } from '@home/pages/role/roles-routing.module';
import { RoleDialogComponent } from '@home/pages/role/role-dialog.component';

@NgModule({
  declarations: [
    RoleDialogComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    RolesRoutingModule
  ]
})
export class RolesModule { }
