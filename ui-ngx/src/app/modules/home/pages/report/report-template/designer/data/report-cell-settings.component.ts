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
import { CellSettings } from '@shared/models/report-configuration.models';
import { ReportAlignment } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';

// CVA for style/CellSettings.java - table header/cell text styling, reused (twice) by C2's
// ReportColumnSettingsComponent (T3, header/cell) and by P1's table settings panel. Mirrors T8's
// ReportDividerConfigComponent/T9's ReportHeadingConfigComponent: the (textAlignment,
// verticalAlignment) pair is edited as a single nested control (matching tb-report-alignment's own
// CVA value shape - it already covers both fields, so it's used directly rather than two bare
// mat-selects) and flattened back onto CellSettings's top-level fields on the way out;
// toCellSettings() is that mapping step. Unlike those per-type right panels (designer/components),
// this is a leaf designer/data widget with no title/section chrome of its own - it's meant to be
// embedded under a caller-supplied label (e.g. "Header"/"Cell" in report-column-settings.component
// .html), the same way tb-report-background-border has no title of its own.
@Component({
    selector: 'tb-report-cell-settings',
    templateUrl: './report-cell-settings.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportCellSettingsComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportCellSettingsComponent implements ControlValueAccessor, OnInit, OnDestroy {

  cellFormGroup: UntypedFormGroup;

  disabled = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: CellSettings) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.cellFormGroup = this.fb.group({
      font: [null],
      color: [null],
      backgroundColor: [null],
      alignment: [{ textAlignment: null, verticalAlignment: null }]
    });
    this.cellFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toCellSettings(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: CellSettings) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.cellFormGroup.disable({emitEvent: false});
    } else {
      this.cellFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: CellSettings): void {
    this.cellFormGroup.reset({
      font: value?.font ?? null,
      color: value?.color ?? null,
      backgroundColor: value?.backgroundColor ?? null,
      alignment: {
        textAlignment: value?.textAlignment ?? null,
        verticalAlignment: value?.verticalAlignment ?? null
      }
    }, {emitEvent: false});
  }

  private toCellSettings(formValue: any): CellSettings {
    const alignment: ReportAlignment = formValue.alignment ?? {};
    return {
      font: formValue.font,
      color: formValue.color,
      backgroundColor: formValue.backgroundColor,
      textAlignment: alignment.textAlignment,
      verticalAlignment: alignment.verticalAlignment
    };
  }
}
