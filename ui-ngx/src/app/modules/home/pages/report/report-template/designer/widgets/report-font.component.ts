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

import { Component, forwardRef, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { Font, FontStyle, FontWeight } from '@shared/models/report-configuration.models';

// Mirrors application/.../service/report/util/FontUtils.ALLOWED_FONT_FAMILIES - the renderer's
// server-side allowlist. Any other value silently falls back to the Roboto default at render
// time, so the family select is limited to exactly this set instead of free text.
export const REPORT_FONT_FAMILIES: string[] = ['Roboto', 'sans-serif', 'serif', 'monospace'];

// CVA for style/Font.java. Mirrors the FormGroup -> propagateChange plumbing of
// kv-map.component.ts. weight/style options are the raw wire values (Font{Weight,Style} are
// already lowercase-string enums), so the form's own value equals Font 1:1 with no mapping step.
@Component({
    selector: 'tb-report-font',
    templateUrl: './report-font.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportFontComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportFontComponent implements ControlValueAccessor, OnInit, OnDestroy {

  fontFormGroup: UntypedFormGroup;

  disabled = false;

  fontWeights = Object.values(FontWeight);
  fontStyles = Object.values(FontStyle);
  fontFamilies = REPORT_FONT_FAMILIES;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: Font) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.fontFormGroup = this.fb.group({
      size: [null],
      weight: [null],
      style: [null],
      family: [null]
    });
    this.fontFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: Font) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: Font) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.fontFormGroup.disable({emitEvent: false});
    } else {
      this.fontFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(font: Font): void {
    this.fontFormGroup.reset({
      size: font?.size ?? null,
      weight: font?.weight ?? null,
      style: font?.style ?? null,
      family: font?.family ?? null
    }, {emitEvent: false});
  }
}
