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
import { FormBuilder, FormGroup } from '@angular/forms';
import { LoginWhiteLabelingParams } from '@shared/models/white-labeling.models';

@Component({
  selector: 'tb-login-wl-settings',
  templateUrl: './login-wl-settings.component.html'
})
export class LoginWlSettingsComponent implements OnInit, OnChanges {
  @Input() params: LoginWhiteLabelingParams;
  @Output() paramsChange = new EventEmitter<LoginWhiteLabelingParams>();

  form: FormGroup;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      pageBackgroundColor: [null],
      darkForeground: [false],
      showNameBottom: [false]
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
      pageBackgroundColor: this.params.pageBackgroundColor || null,
      darkForeground: this.params.darkForeground ?? false,
      showNameBottom: this.params.showNameBottom ?? false
    }, { emitEvent: false });
  }

  private emitParams(): void {
    const v = this.form.value;
    this.paramsChange.emit({
      ...this.params,
      pageBackgroundColor: v.pageBackgroundColor,
      darkForeground: v.darkForeground,
      showNameBottom: v.showNameBottom
    });
  }
}
