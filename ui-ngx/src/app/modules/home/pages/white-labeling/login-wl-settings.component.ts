// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import {
  blankToNull,
  LoginWhiteLabelingParams,
  PlatformVersionPosition,
  platformVersionPositionTranslations
} from '@shared/models/white-labeling.models';
import { WEB_URL_REGEX } from '@shared/models/mobile-app.models';
import { AdvancedCssDialogComponent } from './advanced-css-dialog.component';

@Component({
  standalone: false,
  selector: 'tb-login-wl-settings',
  templateUrl: './login-wl-settings.component.html'
})
export class LoginWlSettingsComponent implements OnInit, OnChanges {
  @Input() params: LoginWhiteLabelingParams;
  @Output() paramsChange = new EventEmitter<LoginWhiteLabelingParams>();

  form: FormGroup;

  readonly webUrlPattern = WEB_URL_REGEX;
  readonly positions = Object.values(PlatformVersionPosition);
  readonly positionTranslations = platformVersionPositionTranslations;

  constructor(private fb: FormBuilder,
              private dialog: MatDialog) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      logoImageUrl: [null],
      logoImageHeight: [null],
      appTitle: [null],
      faviconUrl: [null],
      paletteSettings: [null],
      customCss: [null],
      loginCardColor: [null],
      pageBackgroundColor: [null],
      darkForeground: [false],
      showNameVersion: [false],
      platformName: [null],
      platformVersion: [null],
      namePosition: [PlatformVersionPosition.UNDER_LOGO],
      baseUrl: [null, [Validators.pattern(this.webUrlPattern)]]
    });
    // Same rule as the General tab: with the toggle on, an empty name renders nothing
    // on /login (the slot is guarded by `platformName`), so the toggle would look
    // enabled while doing nothing at all.
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

  private patchForm(): void {
    if (!this.params) { return; }
    this.form.patchValue({
      logoImageUrl: this.params.logoImageUrl || null,
      logoImageHeight: this.params.logoImageHeight || null,
      appTitle: this.params.appTitle || null,
      faviconUrl: this.params.favicon?.url || null,
      paletteSettings: this.params.paletteSettings || null,
      customCss: this.params.customCss || null,
      loginCardColor: this.params.loginCardColor || null,
      pageBackgroundColor: this.params.pageBackgroundColor || null,
      darkForeground: this.params.darkForeground ?? false,
      showNameVersion: this.params.showNameVersion ?? false,
      platformName: this.params.platformName || null,
      platformVersion: this.params.platformVersion || null,
      namePosition: this.params.showNameBottom ? PlatformVersionPosition.BOTTOM : PlatformVersionPosition.UNDER_LOGO,
      baseUrl: this.params.baseUrl || null
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
    this.paramsChange.emit({
      // `domainId` is intentionally NOT listed below: there is no form control for
      // it (the SYS_ADMIN-only Domain picker was dropped as a follow-up — it
      // degraded to a useless "None" for TENANT_ADMIN/CUSTOMER_USER). This spread
      // carries over whatever domainId value was loaded from the backend
      // untouched, so an existing value keeps round-tripping through save even
      // though it is no longer user-editable from this form.
      ...this.params,
      logoImageUrl: blankToNull(v.logoImageUrl),
      logoImageHeight: v.logoImageHeight,
      appTitle: blankToNull(v.appTitle),
      favicon: v.faviconUrl ? { url: v.faviconUrl } : null,
      paletteSettings: v.paletteSettings,
      customCss: blankToNull(v.customCss),
      loginCardColor: blankToNull(v.loginCardColor),
      pageBackgroundColor: blankToNull(v.pageBackgroundColor),
      darkForeground: v.darkForeground,
      showNameVersion: v.showNameVersion,
      platformName: blankToNull(v.platformName),
      platformVersion: blankToNull(v.platformVersion),
      showNameBottom: v.namePosition === PlatformVersionPosition.BOTTOM,
      baseUrl: blankToNull(v.baseUrl)
    });
  }
}
