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
import { ReportPageSettingsComponent } from '@home/pages/report/report-template/designer/report-page-settings.component';
import { ReportInsetsComponent } from '@home/pages/report/report-template/designer/widgets/report-insets.component';
import { ReportFontComponent } from '@home/pages/report/report-template/designer/widgets/report-font.component';
import {
  ReportBackgroundBorderComponent
} from '@home/pages/report/report-template/designer/widgets/report-background-border.component';
import { ReportAlignmentComponent } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';
import { ReportPaletteComponent } from '@home/pages/report/report-template/designer/report-palette.component';
import { ReportCanvasComponent } from '@home/pages/report/report-template/designer/report-canvas.component';
import { ReportPreviewComponent } from '@home/pages/report/report-template/designer/report-preview.component';
import {
  ReportPageBreakConfigComponent
} from '@home/pages/report/report-template/designer/components/report-page-break-config.component';
import {
  ReportDividerConfigComponent
} from '@home/pages/report/report-template/designer/components/report-divider-config.component';
import {
  ReportHeadingConfigComponent
} from '@home/pages/report/report-template/designer/components/report-heading-config.component';

@NgModule({
  declarations: [
    ReportHistoryComponent,
    ReportTemplatesComponent,
    // Unreferenced since C1 Task 3 (add/edit now navigate to ReportTemplateEditorComponent below
    // instead of opening this dialog) - stays declared until a later cleanup task removes it.
    ReportTemplateDialogComponent,
    ReportTemplateEditorComponent,
    // C1 Task 5: right-panel page/template settings shown when no canvas component is selected.
    ReportPageSettingsComponent,
    // C1 Task 4: shared ControlValueAccessor sub-editors, embedded via formControlName by the
    // page-settings/component-config panels (T5, T8-T11).
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent,
    // C1 Task 6: left palette (draggable component library) + center canvas (ordered, reorderable
    // cdkDropList of component cards), hosted side by side by the shell.
    ReportPaletteComponent,
    ReportCanvasComponent,
    // C1 Task 7: whole-template PDF preview pane, shown by the shell instead of the body above when
    // viewMode === 'preview'.
    ReportPreviewComponent,
    // C1 Task 8: first two per-type right-panel config panels (shown when a canvas component is
    // selected), establishing the per-type switch T9-T11 extend.
    ReportPageBreakConfigComponent,
    ReportDividerConfigComponent,
    // C1 Task 9: HEADING right-panel config panel.
    ReportHeadingConfigComponent
  ],
  imports: [
    CommonModule,
    SharedModule,
    HomeComponentsModule,
    ReportRoutingModule
  ],
  exports: [
    ReportPageSettingsComponent,
    ReportInsetsComponent,
    ReportFontComponent,
    ReportBackgroundBorderComponent,
    ReportAlignmentComponent,
    ReportPaletteComponent,
    ReportCanvasComponent,
    ReportPreviewComponent,
    ReportPageBreakConfigComponent,
    ReportDividerConfigComponent,
    ReportHeadingConfigComponent
  ]
})
export class ReportModule { }
