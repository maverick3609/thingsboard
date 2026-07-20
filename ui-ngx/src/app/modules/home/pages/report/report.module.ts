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
import { ReportRoutingModule } from '@home/pages/report/report-routing.module';
import { ReportHistoryComponent } from '@home/pages/report/report-history/report-history.component';
import { ReportTemplatesComponent } from '@home/pages/report/report-template/report-templates.component';
import { ReportTemplateDialogComponent } from '@home/pages/report/report-template/report-template-dialog.component';

@NgModule({
  declarations: [
    ReportHistoryComponent,
    ReportTemplatesComponent,
    ReportTemplateDialogComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    ReportRoutingModule
  ]
})
export class ReportModule { }
