// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { HomeDialogsModule } from '@home/dialogs/home-dialogs.module';
import { InferrixRoutingModule } from '@home/pages/inferrix/inferrix-routing.module';
import { AdoptControllerDialogComponent } from '@home/pages/inferrix/adopt-controller-dialog.component';
import { AssignControllerDialogComponent } from '@home/pages/inferrix/assign-controller-dialog.component';
import { ControllerComponent } from '@home/pages/inferrix/controller/controller.component';
import { ControllerTabsComponent } from '@home/pages/inferrix/controller/controller-tabs.component';
import { ControllerPointsComponent } from '@home/pages/inferrix/controller/controller-points.component';
import { ControllerTemplateDialogComponent } from '@home/pages/inferrix/controller/controller-template-dialog.component';
import { ControllerSettingsComponent } from '@home/pages/inferrix/controller/controller-settings.component';
import { ControllerDiagnosticsComponent } from '@home/pages/inferrix/controller/controller-diagnostics.component';
import { ControllerConfigComponent } from '@home/pages/inferrix/controller/controller-config.component';
import { ControllerConfigRecordDialogComponent }
  from '@home/pages/inferrix/controller/controller-config-record-dialog.component';
import { ControllerSoftwareComponent } from '@home/pages/inferrix/controller/controller-software.component';
import { ControllerLogicComponent } from '@home/pages/inferrix/controller/controller-logic.component';
import { ControllerStatementsComponent } from '@home/pages/inferrix/controller/controller-statements.component';
import { ControllerExpressionComponent } from '@home/pages/inferrix/controller/controller-expression.component';
import { ControllerTuneComponent } from '@home/pages/inferrix/controller/controller-tune.component';
import { ControllerPointWriteDialogComponent }
  from '@home/pages/inferrix/controller/controller-point-write-dialog.component';

@NgModule({
  declarations: [
    AdoptControllerDialogComponent,
    AssignControllerDialogComponent,
    ControllerComponent,
    ControllerTabsComponent,
    ControllerPointsComponent,
    ControllerTemplateDialogComponent,
    ControllerSettingsComponent,
    ControllerDiagnosticsComponent,
    ControllerConfigComponent,
    ControllerConfigRecordDialogComponent,
    ControllerSoftwareComponent,
    ControllerLogicComponent,
    ControllerStatementsComponent,
    ControllerExpressionComponent,
    ControllerTuneComponent,
    ControllerPointWriteDialogComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    HomeDialogsModule,
    InferrixRoutingModule
  ]
})
export class InferrixModule { }
