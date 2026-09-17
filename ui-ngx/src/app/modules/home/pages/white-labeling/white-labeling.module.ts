// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { WhiteLabelingComponent } from './white-labeling.component';
import { GeneralWlSettingsComponent } from './general-wl-settings.component';
import { LoginWlSettingsComponent } from './login-wl-settings.component';
import { PaletteSettingsComponent } from './palette-settings.component';
import { ImageInputComponent } from './image-input.component';
import { LegalContentComponent } from './legal-content.component';
import { MailTemplatesComponent } from './mail-templates.component';
import { PaletteDialogComponent } from './palette-dialog.component';
import { AdvancedCssDialogComponent } from './advanced-css-dialog.component';

@NgModule({
  declarations: [
    WhiteLabelingComponent,
    GeneralWlSettingsComponent,
    LoginWlSettingsComponent,
    PaletteSettingsComponent,
    ImageInputComponent,
    LegalContentComponent,
    MailTemplatesComponent,
    PaletteDialogComponent,
    AdvancedCssDialogComponent
  ],
  imports: [
    CommonModule,
    SharedModule
  ]
})
export class WhiteLabelingModule {}
