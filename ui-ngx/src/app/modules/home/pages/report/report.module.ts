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
import { ReportTemplateEditorComponent } from '@home/pages/report/report-template/designer/report-template-editor.component';
import { ReportInsetsComponent } from '@home/pages/report/report-template/designer/widgets/report-insets.component';
import { ReportFontComponent } from '@home/pages/report/report-template/designer/widgets/report-font.component';
import {
  ReportBackgroundBorderComponent
} from '@home/pages/report/report-template/designer/widgets/report-background-border.component';
import { ReportAlignmentComponent } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';

@NgModule({
  declarations: [
    ReportHistoryComponent,
    ReportTemplatesComponent,
    // Unreferenced since C1 Task 3 (add/edit now navigate to ReportTemplateEditorComponent below
    // instead of opening this dialog) - stays declared until a later cleanup task removes it.
    ReportTemplateDialogComponent,
    ReportTemplateEditorComponent,
    // C1 Task 4: shared ControlValueAccessor sub-editors, embedded via formControlName by the
    // page-settings/component-config panels (T5, T8-T11).
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    ReportRoutingModule
  ],
  exports: [
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent
  ]
})
export class ReportModule { }
