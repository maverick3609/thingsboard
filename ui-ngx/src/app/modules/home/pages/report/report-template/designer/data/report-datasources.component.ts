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

import { Component, forwardRef, Input, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormArray, UntypedFormBuilder, UntypedFormControl } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { coerceBoolean } from '@shared/decorators/coercion';
import { DataSource, DataSourceType } from '@shared/models/report-configuration.models';
import { IAliasController } from '@core/api/widget-api.models';
import { widgetType } from '@shared/models/widget.models';

// CVA for DataSource.java[] - an add/remove list of this file's own sibling tb-report-datasource,
// consumed by ENTITY_TABLE/TIME_SERIES_TABLE's `dataSources` field (AbstractDataReportComponent -
// report-configuration.models.ts:331 - via Task 11's panels).
//
// Deliberately NOT a cdkDropList-reorderable list like the platform's tb-datasources
// (config/datasources.component.ts) - the brief only asks for add/remove, and report table rows
// don't have an order-sensitive "primary datasource" concept the platform's
// widgetType.timeseries/hasAdditionalLatestDataKeys logic cares about (this repo's DataSource has no
// such distinction; every dataSources[] entry is a peer, each contributing its own resolved
// entity/device rows to the table). Reordering the array only reorders which entity's rows are
// fetched first, not anything semantically load-bearing - it can be added later without a CVA
// contract change if a real need shows up.
//
// Each array slot is a plain FormControl holding the whole DataSource object - tb-report-datasource
// is itself a complete CVA, so this composite never needs to know its child's internal FormGroup
// shape (the same "opaque nested CVA" composition every other designer component in this family
// uses, e.g. how report-data-keys.component.html hosts tb-report-column-settings per key without
// reaching into it).
@Component({
    selector: 'tb-report-datasources',
    templateUrl: './report-datasources.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportDatasourcesComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportDatasourcesComponent implements ControlValueAccessor, OnInit, OnDestroy {

  dsFormArray: UntypedFormArray;

  disabled = false;

  // Pass-through Inputs, forwarded verbatim to every child tb-report-datasource - see that
  // component's own header comment for what each drives.
  @Input()
  aliasController: IAliasController;

  @Input()
  widgetType: widgetType = widgetType.latest;

  @Input()
  @coerceBoolean()
  showAlarmFilter = false;

  // Task 11 carry: pure pass-through to every child tb-report-datasource - see that component's own
  // header comment for the backward-compatibility contract (undefined -> all 4 options).
  @Input()
  allowedDataSourceTypes?: DataSourceType[];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: DataSource[]) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.dsFormArray = this.fb.array([]);
    this.dsFormArray.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: DataSource[]) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: DataSource[]) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.dsFormArray.disable({emitEvent: false});
    } else {
      this.dsFormArray.enable({emitEvent: false});
    }
  }

  writeValue(value: DataSource[]): void {
    this.dsFormArray.clear({emitEvent: false});
    (value ?? []).forEach(dataSource => {
      this.dsFormArray.push(this.fb.control(dataSource), {emitEvent: false});
    });
  }

  get controls(): UntypedFormControl[] {
    return this.dsFormArray.controls as UntypedFormControl[];
  }

  trackByIndex(index: number): number {
    return index;
  }

  addDataSource(): void {
    this.dsFormArray.push(this.fb.control({} as DataSource));
  }

  removeDataSource(index: number): void {
    this.dsFormArray.removeAt(index);
  }
}
