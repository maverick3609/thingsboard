///
/// Copyright © 2016-2026 The Thingsboard Authors
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

import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { blankToNull, WhiteLabelingParams } from '@shared/models/white-labeling.models';
import { AdvancedCssDialogComponent } from './advanced-css-dialog.component';

@Component({
  standalone: false,
  selector: 'tb-general-wl-settings',
  templateUrl: './general-wl-settings.component.html'
})
export class GeneralWlSettingsComponent implements OnInit, OnChanges {
  @Input() params: WhiteLabelingParams;
  @Output() paramsChange = new EventEmitter<WhiteLabelingParams>();

  form: FormGroup;

  constructor(private fb: FormBuilder,
              private dialog: MatDialog) {}

  openAdvancedCss(): void {
    this.dialog.open<AdvancedCssDialogComponent, { css: string }, string>(AdvancedCssDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: { css: this.form.get('customCss').value }
    }).afterClosed().subscribe(css => {
      if (css !== undefined) {
        this.form.get('customCss').setValue(css);
      }
    });
  }

  ngOnInit(): void {
    this.form = this.fb.group({
      logoImageUrl: [null],
      logoImageHeight: [null],
      appTitle: [null],
      faviconUrl: [null],
      helpLinkBaseUrl: [null],
      uiHelpBaseUrl: [null],
      enableHelpLinks: [true],
      showNameVersion: [false],
      platformName: [null],
      platformVersion: [null],
      customCss: [null],
      hideConnectivityDialog: [false],
      paletteSettings: [null]
    });
    this.form.get('showNameVersion').valueChanges.subscribe((showNameVersion: boolean) => {
      this.updatePlatformNameValidators(showNameVersion);
    });
    this.form.valueChanges.subscribe(() => this.emitParams());
    this.patchForm();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.params && this.form) {
      this.patchForm();
    }
  }

  private patchForm(): void {
    if (!this.params) { return; }
    this.form.patchValue({
      logoImageUrl: this.params.logoImageUrl || null,
      logoImageHeight: this.params.logoImageHeight,
      appTitle: this.params.appTitle || null,
      faviconUrl: this.params.favicon?.url || null,
      helpLinkBaseUrl: this.params.helpLinkBaseUrl || null,
      uiHelpBaseUrl: this.params.uiHelpBaseUrl || null,
      enableHelpLinks: this.params.enableHelpLinks ?? true,
      showNameVersion: this.params.showNameVersion ?? false,
      platformName: this.params.platformName || null,
      platformVersion: this.params.platformVersion || null,
      customCss: this.params.customCss || null,
      hideConnectivityDialog: this.params.hideConnectivityDialog ?? false,
      paletteSettings: this.params.paletteSettings
    }, { emitEvent: false });
    this.updatePlatformNameValidators(this.params.showNameVersion ?? false);
  }

  private updatePlatformNameValidators(showNameVersion: boolean): void {
    const platformNameControl = this.form.get('platformName');
    platformNameControl.setValidators(showNameVersion ? [Validators.required] : []);
    platformNameControl.updateValueAndValidity({ emitEvent: false });
  }

  private emitParams(): void {
    const v = this.form.value;
    const params: WhiteLabelingParams = {
      ...this.params,
      logoImageUrl: blankToNull(v.logoImageUrl),
      logoImageHeight: v.logoImageHeight,
      appTitle: blankToNull(v.appTitle),
      favicon: v.faviconUrl ? { url: v.faviconUrl } : null,
      helpLinkBaseUrl: blankToNull(v.helpLinkBaseUrl),
      uiHelpBaseUrl: blankToNull(v.uiHelpBaseUrl),
      enableHelpLinks: v.enableHelpLinks,
      showNameVersion: v.showNameVersion,
      platformName: blankToNull(v.platformName),
      platformVersion: blankToNull(v.platformVersion),
      customCss: blankToNull(v.customCss),
      hideConnectivityDialog: v.hideConnectivityDialog,
      paletteSettings: v.paletteSettings
    };
    this.paramsChange.emit(params);
  }
}
