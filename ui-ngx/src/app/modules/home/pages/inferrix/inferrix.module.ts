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
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { HomeComponentsModule } from '@home/components/home-components.module';
import { InferrixRoutingModule } from '@home/pages/inferrix/inferrix-routing.module';
import { ControllersComponent } from '@home/pages/inferrix/controllers.component';
import { DiscoveredControllersComponent } from '@home/pages/inferrix/discovered-controllers.component';
import { AdoptControllerDialogComponent } from '@home/pages/inferrix/adopt-controller-dialog.component';
import { ControllerComponent } from '@home/pages/inferrix/controller/controller.component';
import { ControllerPointsComponent } from '@home/pages/inferrix/controller/controller-points.component';
import { ControllerSettingsComponent } from '@home/pages/inferrix/controller/controller-settings.component';
import { ControllerDiagnosticsComponent } from '@home/pages/inferrix/controller/controller-diagnostics.component';
import { ControllerConfigComponent } from '@home/pages/inferrix/controller/controller-config.component';
import { ControllerConfigRecordDialogComponent }
  from '@home/pages/inferrix/controller/controller-config-record-dialog.component';
import { ControllerSoftwareComponent } from '@home/pages/inferrix/controller/controller-software.component';

@NgModule({
  declarations: [
    ControllersComponent,
    DiscoveredControllersComponent,
    AdoptControllerDialogComponent,
    ControllerComponent,
    ControllerPointsComponent,
    ControllerSettingsComponent,
    ControllerDiagnosticsComponent,
    ControllerConfigComponent,
    ControllerConfigRecordDialogComponent,
    ControllerSoftwareComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    InferrixRoutingModule
  ]
})
export class InferrixModule { }
