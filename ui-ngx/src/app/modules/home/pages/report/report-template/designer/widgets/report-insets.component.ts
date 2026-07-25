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
import { Insets } from '@shared/models/report-configuration.models';

// CVA for style/Insets.java (page margins, component margins/paddings). Mirrors the
// FormGroup -> propagateChange plumbing of kv-map.component.ts; unlike kv-map's keyVals array,
// the form's own value already has the {left,right,top,bottom} shape of Insets 1:1, so
// valueChanges is propagated straight through with no mapping step.
@Component({
    selector: 'tb-report-insets',
    templateUrl: './report-insets.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportInsetsComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportInsetsComponent implements ControlValueAccessor, OnInit, OnDestroy {

  insetsFormGroup: UntypedFormGroup;

  disabled = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: Insets) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.insetsFormGroup = this.fb.group({
      left: [0],
      right: [0],
      top: [0],
      bottom: [0]
    });
    this.insetsFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: Insets) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: Insets) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.insetsFormGroup.disable({emitEvent: false});
    } else {
      this.insetsFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(insets: Insets): void {
    this.insetsFormGroup.reset({
      left: insets?.left ?? 0,
      right: insets?.right ?? 0,
      top: insets?.top ?? 0,
      bottom: insets?.bottom ?? 0
    }, {emitEvent: false});
  }
}
