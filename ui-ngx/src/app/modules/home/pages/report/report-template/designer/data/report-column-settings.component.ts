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
import { ColumnSettings } from '@shared/models/report-configuration.models';

// CVA for style/ColumnSettings.java - the DataKeySettingsType.COLUMN variant of the DataKeySettings
// union (report-configuration.models.ts:271-278), authored by P1's table settings panel and T7's
// data-keys glue per data key. header/cell are each a full nested CellSettings (T3's
// ReportCellSettingsComponent above) rather than duplicating its fields here - composed the same
// way T8/T9 nest tb-report-insets/tb-report-background-border directly via formControlName. Always
// emits the `type: 'COLUMN'` EXISTING_PROPERTY discriminator (DataKeySettingsType) so a value this
// component produces round-trips through the ColumnSettings|DefaultDataKeySettings union.
@Component({
    selector: 'tb-report-column-settings',
    templateUrl: './report-column-settings.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportColumnSettingsComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportColumnSettingsComponent implements ControlValueAccessor, OnInit, OnDestroy {

  columnFormGroup: UntypedFormGroup;

  disabled = false;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ColumnSettings) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.columnFormGroup = this.fb.group({
      columnWidth: [null],
      header: [null],
      cell: [null]
    });
    this.columnFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toColumnSettings(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ColumnSettings) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.columnFormGroup.disable({emitEvent: false});
    } else {
      this.columnFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: ColumnSettings): void {
    this.columnFormGroup.reset({
      columnWidth: value?.columnWidth ?? null,
      header: value?.header ?? null,
      cell: value?.cell ?? null
    }, {emitEvent: false});
  }

  private toColumnSettings(formValue: any): ColumnSettings {
    return {
      type: 'COLUMN',
      columnWidth: formValue.columnWidth,
      header: formValue.header,
      cell: formValue.cell
    };
  }
}
