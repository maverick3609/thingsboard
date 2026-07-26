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
import {
  Heading,
  ReportComponentTableSettings,
  TableSortDirection,
  TableSortOrder
} from '@shared/models/report-configuration.models';
import { ReportAlignment } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';

// CVA for components/AbstractTableReportComponent.java's tableSettings field
// (ReportComponentTableSettings) - the "show heading" + table-heading style + sort-order block
// shared by every table-shaped component (ENTITY/ALARM/TIME_SERIES), consumed by P1's generic
// table config panel (Task 11). Mirrors T9's ReportHeadingConfigComponent: `tableHeading` is a
// genuinely nested Heading object (style/Heading.java - text/font/color/alignment/height, distinct
// from the page-level HeadingComponent T9 edits), so it's wired as its own nested FormGroup - the
// same text/font/color/alignment/height fields T9 wires, one level deeper under `tableHeading`
// instead of at the form's own top level - with the alignment pair flattened back onto Heading's
// flat textAlignment/verticalAlignment fields by toTableSettings() on the way out, exactly as T9's
// toHeadingComponent() does. `tableSortOrder` is a second nested FormGroup (column + direction)
// that already matches TableSortOrder 1:1, so no flattening is needed there. Unlike T3's
// ColumnSettings (header/cell delegated wholesale to a child tb-report-cell-settings CVA), there is
// no premade "Heading" CVA to delegate tableHeading to, so its fields are wired inline here.
@Component({
    selector: 'tb-report-table-settings',
    templateUrl: './report-table-settings.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportTableSettingsComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportTableSettingsComponent implements ControlValueAccessor, OnInit, OnDestroy {

  tableFormGroup: UntypedFormGroup;

  disabled = false;

  tableSortDirectionEnum = TableSortDirection;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportComponentTableSettings) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.tableFormGroup = this.fb.group({
      showTableHeading: [null],
      tableHeading: this.fb.group({
        text: [null],
        font: [null],
        color: [null],
        alignment: [{ textAlignment: null, verticalAlignment: null }],
        height: [null]
      }),
      tableSortOrder: this.fb.group({
        column: [null],
        direction: [null]
      })
    });
    this.tableFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toTableSettings(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportComponentTableSettings) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.tableFormGroup.disable({emitEvent: false});
    } else {
      this.tableFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: ReportComponentTableSettings): void {
    const tableHeading: Heading = value?.tableHeading ?? {};
    const tableSortOrder: TableSortOrder = value?.tableSortOrder ?? {};
    this.tableFormGroup.reset({
      showTableHeading: value?.showTableHeading ?? null,
      tableHeading: {
        text: tableHeading.text ?? null,
        font: tableHeading.font ?? null,
        color: tableHeading.color ?? null,
        alignment: {
          textAlignment: tableHeading.textAlignment ?? null,
          verticalAlignment: tableHeading.verticalAlignment ?? null
        },
        height: tableHeading.height ?? null
      },
      tableSortOrder: {
        column: tableSortOrder.column ?? null,
        direction: tableSortOrder.direction ?? null
      }
    }, {emitEvent: false});
  }

  private toTableSettings(value: any): ReportComponentTableSettings {
    const tableHeadingValue = value.tableHeading ?? {};
    const alignment: ReportAlignment = tableHeadingValue.alignment ?? {};
    const tableHeading: Heading = {
      text: tableHeadingValue.text,
      font: tableHeadingValue.font,
      color: tableHeadingValue.color,
      textAlignment: alignment.textAlignment,
      verticalAlignment: alignment.verticalAlignment,
      height: tableHeadingValue.height
    };
    return {
      showTableHeading: value.showTableHeading,
      tableHeading,
      tableSortOrder: value.tableSortOrder
    };
  }
}
