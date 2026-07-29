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
import { LoginWhiteLabelingParams } from '@shared/models/white-labeling.models';
import { WEB_URL_REGEX } from '@shared/models/mobile-app.models';

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

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      logoImageUrl: [null],
      logoImageHeight: [null],
      loginCardColor: [null],
      pageBackgroundColor: [null],
      darkForeground: [false],
      showNameBottom: [false],
      baseUrl: [null, [Validators.pattern(this.webUrlPattern)]]
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
      logoImageHeight: this.params.logoImageHeight || null,
      loginCardColor: this.params.loginCardColor || null,
      pageBackgroundColor: this.params.pageBackgroundColor || null,
      darkForeground: this.params.darkForeground ?? false,
      showNameBottom: this.params.showNameBottom ?? false,
      baseUrl: this.params.baseUrl || null
    }, { emitEvent: false });
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
      logoImageUrl: v.logoImageUrl,
      logoImageHeight: v.logoImageHeight,
      loginCardColor: v.loginCardColor,
      pageBackgroundColor: v.pageBackgroundColor,
      darkForeground: v.darkForeground,
      showNameBottom: v.showNameBottom,
      baseUrl: v.baseUrl
    });
  }
}
