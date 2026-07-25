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
import { ReportComponentLayout } from '@shared/models/report-configuration.models';

// The 4 style fields of ReportComponentLayout (components/AbstractLayoutReportComponent.java) this
// widget edits; margins/paddings are the other 2 layout fields, edited separately via
// tb-report-insets.
export type ReportBackgroundBorder = Pick<ReportComponentLayout, 'background' | 'borderWidth' | 'borderRadius' | 'borderColor'>;

// CVA for the background/border subset of ReportComponentLayout. Mirrors the FormGroup ->
// propagateChange plumbing of kv-map.component.ts; the form's own value already has the
// {background,borderWidth,borderRadius,borderColor} shape 1:1, so no mapping step is needed.
// Colors go through tb-color-input (not tb-color-picker, which is the full picker panel meant to
// be hosted in a dialog/popover, not embedded inline) - writeValue(undefined) leaves it blank
// rather than forcing a default color, so an unset background/border stays unset.
@Component({
    selector: 'tb-report-background-border',
    templateUrl: './report-background-border.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportBackgroundBorderComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportBackgroundBorderComponent implements ControlValueAccessor, OnInit, OnDestroy {

  backgroundBorderFormGroup: UntypedFormGroup;

  disabled = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportBackgroundBorder) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.backgroundBorderFormGroup = this.fb.group({
      background: [null],
      borderWidth: [null],
      borderRadius: [null],
      borderColor: [null]
    });
    this.backgroundBorderFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: ReportBackgroundBorder) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportBackgroundBorder) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.backgroundBorderFormGroup.disable({emitEvent: false});
    } else {
      this.backgroundBorderFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: ReportBackgroundBorder): void {
    this.backgroundBorderFormGroup.reset({
      background: value?.background ?? null,
      borderWidth: value?.borderWidth ?? null,
      borderRadius: value?.borderRadius ?? null,
      borderColor: value?.borderColor ?? null
    }, {emitEvent: false});
  }
}
