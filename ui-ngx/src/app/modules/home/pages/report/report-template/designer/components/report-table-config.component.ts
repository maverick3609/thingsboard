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
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  AlarmTableComponent,
  ColumnSettings,
  DataSourceType,
  EntityTableComponent,
  ReportComponent,
  ReportComponentTableSettings,
  TimeseriesTableComponent
} from '@shared/models/report-configuration.models';
import { IAliasController } from '@core/api/widget-api.models';
import { widgetType } from '@shared/models/widget.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

type ReportTableComponentValue = EntityTableComponent | AlarmTableComponent | TimeseriesTableComponent;

// CVA shared by the 3 table-shaped components (components/EntityTableComponent.java /
// AlarmTableComponent.java / TimeseriesTableComponent.java) - the shell's right-panel content when
// the selected canvas component is ENTITY_TABLE/ALARM_TABLE/TIME_SERIES_TABLE
// (report-template-editor.component.html). One FormGroup superset (mirroring T8's
// ReportDatasourceComponent: a single form wide enough for every `type`'s fields, with per-type
// relevance filtered on the way OUT rather than the form rebuilt per type) hosts every field any of
// the 3 types can carry; `@Input() componentType` selects which fields the TEMPLATE shows and which
// ones toComponent() actually emits. The shell's *ngIf covering all 3 types stays true across a
// canvas-selection change from one table type to another, so this component instance persists
// (only its Inputs/writeValue()'d value change, no fresh ngOnInit) - the superset-form design means
// that edge case needs no special handling: the same live form just gets re-shaped by the new
// componentType on the next toComponent() call.
//
// Nested sub-editors (tb-report-datasources/tb-report-datasource, tb-report-timewindow,
// tb-report-table-settings, tb-report-insets x2, tb-report-background-border) are each wired as ONE
// opaque FormControl holding that child's OWN full CVA value - the same "opaque nested CVA"
// composition T8/T9/C1's panels already use for tb-report-background-border etc. - never decomposed
// into this form's own sub-fields. tableSettings' 3 fields (showTableHeading/tableHeading/
// tableSortOrder - ReportComponentTableSettings) and the layout sextet (margins/paddings/background/
// borderWidth/borderRadius/borderColor - ReportComponentLayout) are flattened back onto the emitted
// component's own top level on the way out, exactly as backgroundBorder is elsewhere in this family.
//
// timestampColumnSettings (TIME_SERIES_TABLE only) has no consumed sub-editor per the coordinator's
// task-11-interfaces.md notes (tb-report-column-settings exists but isn't wired into this panel) -
// like C1's `dataSources` passthrough on IMAGE/HEADING/RICH_TEXT, it's stashed on writeValue() and
// re-attached untouched by toComponent(), never exposed as a form control.
//
// PER-TYPE OUTPUT SHAPE (toComponent(), the single source of truth for "never fabricate an
// irrelevant key" - the brief's hard requirement that ALARM_TABLE never emits a `dataSources` key):
//  - ENTITY_TABLE: type, dataSources, tableSettings fields, layout fields. No alarmSource/timewindow/
//    showTimestamp/timestampLabel/timestampPattern/timestampColumnSettings - EntityTableComponent
//    declares none of them.
//  - ALARM_TABLE: type, alarmSource, timewindow, tableSettings fields, layout fields. No
//    `dataSources` key at all (AlarmTableComponent.java's getDataSources() is derived+@JsonIgnore'd -
//    report-configuration.models.ts's own header comment on AlarmTableComponent).
//  - TIME_SERIES_TABLE: type, dataSources, timewindow, showTimestamp, timestampLabel,
//    timestampPattern, timestampColumnSettings, tableSettings fields, layout fields. No alarmSource.
@Component({
    selector: 'tb-report-table-config',
    templateUrl: './report-table-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportTableConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportTableConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  formGroup: UntypedFormGroup;

  disabled = false;

  // Set by the shell from `selected.type` - selects which of the 3 shapes this panel edits.
  @Input()
  componentType: ReportComponent['type'];

  // Forwarded to tb-report-datasources/tb-report-datasource - see Task 9's createReportAliasController
  // (created once by the shell in ngOnInit, threaded down here).
  @Input()
  aliasController: IAliasController;

  widgetTypeEnum = widgetType;

  // Only DEVICE/ENTITY datasources yield table rows in the Inferrix renderer (R2b's query builder) -
  // ENTITY_COUNT/ALARM_COUNT are scalar-count-only shapes with no per-row data, so this table panel
  // (Task 8 carry - the new allowedDataSourceTypes @Input) narrows both tb-report-datasources' picker
  // and the single ALARM alarmSource picker to these 2, rather than the full 4-option generic picker.
  readonly tableDataSourceTypes: DataSourceType[] = [DataSourceType.DEVICE, DataSourceType.ENTITY];

  // Stashed on writeValue(), re-attached untouched by toComponent() - see class header.
  private timestampColumnSettings: ColumnSettings;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportTableComponentValue) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.formGroup = this.fb.group({
      dataSources: [null],
      alarmSource: [null],
      timewindow: [null],
      tableSettings: [{ showTableHeading: null, tableHeading: null, tableSortOrder: null }],
      showTimestamp: [null],
      timestampLabel: [null],
      timestampPattern: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.formGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportTableComponentValue) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.formGroup.disable({emitEvent: false});
    } else {
      this.formGroup.enable({emitEvent: false});
    }
  }

  writeValue(component: ReportTableComponentValue): void {
    // `any`, not a Partial<A & B & C> intersection: A/B/C's `type` literals are mutually exclusive,
    // so TS collapses that intersection to `never` - component is a real union, and every field read
    // below only exists on 1-2 of its 3 members, which a discriminated union access can't express
    // without a per-field `in`/narrowing check for what is, here, a purely defensive read.
    const c: any = component ?? {};
    this.timestampColumnSettings = c.timestampColumnSettings;
    this.formGroup.reset({
      dataSources: c.dataSources ?? null,
      alarmSource: c.alarmSource ?? null,
      timewindow: c.timewindow ?? null,
      tableSettings: {
        showTableHeading: c.showTableHeading ?? null,
        tableHeading: c.tableHeading ?? null,
        tableSortOrder: c.tableSortOrder ?? null
      },
      showTimestamp: c.showTimestamp ?? null,
      timestampLabel: c.timestampLabel ?? null,
      timestampPattern: c.timestampPattern ?? null,
      margins: c.margins ?? null,
      paddings: c.paddings ?? null,
      backgroundBorder: {
        background: c.background ?? null,
        borderWidth: c.borderWidth ?? null,
        borderRadius: c.borderRadius ?? null,
        borderColor: c.borderColor ?? null
      }
    }, {emitEvent: false});
  }

  get isEntityTable(): boolean {
    return this.componentType === 'ENTITY_TABLE';
  }

  get isAlarmTable(): boolean {
    return this.componentType === 'ALARM_TABLE';
  }

  get isTimeSeriesTable(): boolean {
    return this.componentType === 'TIME_SERIES_TABLE';
  }

  get showTimewindow(): boolean {
    return this.isAlarmTable || this.isTimeSeriesTable;
  }

  // tb-report-data-keys' own default (widgetType.latest) already matches ENTITY_TABLE's static
  // snapshot nature - TIME_SERIES_TABLE is the one type that needs historical (timeseries) data
  // keys instead. ALARM_TABLE's alarmSource binds widgetType.alarm directly in the template (a
  // constant for that one branch, not worth a getter).
  get dataWidgetType(): widgetType {
    return this.isTimeSeriesTable ? widgetType.timeseries : widgetType.latest;
  }

  private toComponent(value: any): ReportTableComponentValue {
    const tableSettings: ReportComponentTableSettings = value.tableSettings ?? {};
    const backgroundBorder: ReportBackgroundBorder = value.backgroundBorder ?? {};
    const tableSettingsFields: ReportComponentTableSettings = {
      showTableHeading: tableSettings.showTableHeading,
      tableHeading: tableSettings.tableHeading,
      tableSortOrder: tableSettings.tableSortOrder
    };
    const layoutFields = {
      margins: value.margins,
      paddings: value.paddings,
      background: backgroundBorder.background,
      borderWidth: backgroundBorder.borderWidth,
      borderRadius: backgroundBorder.borderRadius,
      borderColor: backgroundBorder.borderColor
    };

    if (this.isAlarmTable) {
      const alarmTable: AlarmTableComponent = {
        type: 'ALARM_TABLE',
        alarmSource: value.alarmSource,
        timewindow: value.timewindow,
        ...tableSettingsFields,
        ...layoutFields
      };
      return alarmTable;
    }
    if (this.isTimeSeriesTable) {
      const timeSeriesTable: TimeseriesTableComponent = {
        type: 'TIME_SERIES_TABLE',
        dataSources: value.dataSources,
        timewindow: value.timewindow,
        showTimestamp: value.showTimestamp,
        timestampLabel: value.timestampLabel,
        timestampPattern: value.timestampPattern,
        timestampColumnSettings: this.timestampColumnSettings,
        ...tableSettingsFields,
        ...layoutFields
      };
      return timeSeriesTable;
    }
    const entityTable: EntityTableComponent = {
      type: 'ENTITY_TABLE',
      dataSources: value.dataSources,
      ...tableSettingsFields,
      ...layoutFields
    };
    return entityTable;
  }
}
