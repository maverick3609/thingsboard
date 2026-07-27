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
import { coerceBoolean } from '@shared/decorators/coercion';
import { isDefinedAndNotNull } from '@core/utils';
import { DataSource, DataSourceType } from '@shared/models/report-configuration.models';
import { IAliasController } from '@core/api/widget-api.models';
import { widgetType } from '@shared/models/widget.models';
import { EntityType } from '@shared/models/entity-type.models';

// CVA for DataSource.java (report-configuration.models.ts:227-235) - the integration hub wiring
// Task 6's tb-report-alarm-filter, Task 7's tb-report-data-keys, and 3 platform leaf pickers
// (tb-entity-autocomplete / tb-entity-alias-select / tb-filter-select) behind a `type` switch,
// mirroring the platform's own widget/config/datasource.component.html layout
// (c2-reuse-cva-notes.md's "Datasource layout pattern") WITHOUT reusing that component itself - it
// hard-injects WidgetConfigComponent, a live dashboard-editing context this report designer doesn't
// have.
//
// All 4 leaf pickers happen to speak the SAME wire shape as this repo's frozen DataSource fields
// (deviceId/entityAliasId/filterId are plain id strings on both sides; tb-report-data-keys' own CVA
// value already IS this repo's DataKey[] per Task 7), so - unlike Tasks 5/6/7 - this component needs
// no bidirectional value mapping, only a per-`type` relevance filter on the way out (toDataSource()).
//
// PER-TYPE FIELD RELEVANCE (traced against the backend query builder -
// service/report/render/ReportQueryUtils.java's toAlarmDataQuery()/toEntityDataQuery()/
// buildEntityFilter() - and the PE default-config fixture, designer/fixtures/pe-default-configs.ts's
// `entityTable`/`timeSeriesTable`/`alarmTable` datasource literals, not just guessed from the brief):
//  - DEVICE: deviceId, dataKeys, latestDataKeys. (buildEntityFilter() resolves
//    DeviceId.fromString(dataSource.getDeviceId()) directly - no alias/filter involved.)
//  - ENTITY: entityAliasId, filterId, dataKeys, latestDataKeys. (buildAliasBasedFilter() +
//    findKeyFilters() both key off entityAliasId/filterId.)
//  - ENTITY_COUNT: entityAliasId, filterId only - no dataKeys (a scalar count query has no columns;
//    brief: "dataKeys optional/omitted").
//  - ALARM_COUNT: entityAliasId, alarmFilterConfig only - the alarm filter defines what's counted.
// `showAlarmFilter` (below) widens alarmFilterConfig's relevance to EVERY type, for the one caller
// that needs it regardless of type: ALARM_TABLE's `alarmSource` (Task 11) is itself a plain
// device/entity DataSource (report-configuration.models.ts:396) that ALSO always carries
// alarmFilterConfig - confirmed by both AlarmTableRenderer's data path (ReportQueryUtils.
// toAlarmDataQuery() reads alarmSource.getDataKeys() for columns AND
// alarmSource.getAlarmFilterConfig() for scoping, unconditionally) and the PE fixture's own
// `alarmTable.alarmSource: { type: 'entity', alarmFilterConfig: {}, dataKeys: [...] }`. Task 11 sets
// `[showAlarmFilter]="true"` when binding this component to an alarmSource; the plain
// dataSources[]-member use (tb-report-datasources below, ENTITY_TABLE/TIME_SERIES_TABLE) leaves it
// at its default `false`.
//
// The four `is*Relevant` predicates below are the single source of truth for this table - both
// toDataSource() (what gets written out) and the component's own template-visibility getters
// delegate to them, so the two can never drift apart.
function isEntityAliasRelevant(type: DataSourceType): boolean {
  return type === DataSourceType.ENTITY || type === DataSourceType.ENTITY_COUNT || type === DataSourceType.ALARM_COUNT;
}

function isFilterRelevant(type: DataSourceType): boolean {
  return type === DataSourceType.ENTITY || type === DataSourceType.ENTITY_COUNT;
}

function isDataKeysRelevant(type: DataSourceType): boolean {
  return type === DataSourceType.DEVICE || type === DataSourceType.ENTITY;
}

function isAlarmFilterRelevant(type: DataSourceType, showAlarmFilter: boolean): boolean {
  return type === DataSourceType.ALARM_COUNT || showAlarmFilter;
}

// Pure mapping function - exported standalone so the per-type relevance filter is directly
// unit-testable without the Angular CVA/DOM machinery (mirrors report-timewindow.component.ts's
// reportToPlatformTimewindow / report-alarm-filter.component.ts's reportToPlatformAlarmFilter).
// Every field is isDefinedAndNotNull-guarded AND type-relevance-guarded before being copied onto the
// result, so: (a) a field left null/undefined never materializes as an explicit `field: undefined`
// key (this project's toEqual does not treat the two as equivalent - see report-data-keys.component
// .ts's header comment), and (b) a field that's SET but irrelevant to the current `type` (e.g. a
// stale deviceId left over from before the user switched from DEVICE to ENTITY) is never fabricated
// into the output - "leave irrelevant ones unset/null" per the brief.
export function toDataSource(value: any, showAlarmFilter = false): DataSource {
  const type: DataSourceType = value?.type;
  const result: DataSource = {};
  if (isDefinedAndNotNull(type)) {
    result.type = type;
  }
  if (type === DataSourceType.DEVICE && isDefinedAndNotNull(value.deviceId)) {
    result.deviceId = value.deviceId;
  }
  if (isEntityAliasRelevant(type) && isDefinedAndNotNull(value.entityAliasId)) {
    result.entityAliasId = value.entityAliasId;
  }
  if (isFilterRelevant(type) && isDefinedAndNotNull(value.filterId)) {
    result.filterId = value.filterId;
  }
  if (isDataKeysRelevant(type)) {
    if (isDefinedAndNotNull(value.dataKeys)) {
      result.dataKeys = value.dataKeys;
    }
    if (isDefinedAndNotNull(value.latestDataKeys)) {
      result.latestDataKeys = value.latestDataKeys;
    }
  }
  if (isAlarmFilterRelevant(type, showAlarmFilter) && isDefinedAndNotNull(value.alarmFilterConfig)) {
    result.alarmFilterConfig = value.alarmFilterConfig;
  }
  return result;
}

@Component({
    selector: 'tb-report-datasource',
    templateUrl: './report-datasource.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportDatasourceComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportDatasourceComponent implements ControlValueAccessor, OnInit, OnDestroy {

  dsFormGroup: UntypedFormGroup;

  disabled = false;

  dataSourceTypeEnum = DataSourceType;
  widgetTypeEnum = widgetType;
  entityTypeEnum = EntityType;

  // REQUIRED by tb-entity-alias-select/tb-filter-select (both NPE without one - see Task 9's
  // createReportAliasController) and forwarded into tb-report-data-keys. Supplied by the host panel
  // (Task 11) / report-template-editor shell, built once from the report template's own
  // entityAliases/filters.
  @Input()
  aliasController: IAliasController;

  // Forwarded to tb-report-data-keys' own same-named Input (its default is `widgetType.latest`,
  // matching a static report snapshot table) - Task 11 overrides per component type (`timeseries`
  // for TIME_SERIES_TABLE, `alarm` for ALARM_TABLE's alarmSource).
  @Input()
  widgetType: widgetType = widgetType.latest;

  // See the class-level "PER-TYPE FIELD RELEVANCE" comment - widens alarmFilterConfig visibility
  // beyond ALARM_COUNT for the ALARM_TABLE alarmSource use (Task 11).
  @Input()
  @coerceBoolean()
  showAlarmFilter = false;

  // Task 11 carry: BACKWARD-COMPATIBLE - undefined (the default) renders all 4 mat-options exactly
  // as before this Input existed. Task 11's table config panel sets this to [device, entity] since
  // ENTITY_COUNT/ALARM_COUNT yield no table rows in the Inferrix renderer (R2b's query builder) -
  // an Inferrix UX-honesty narrowing over PE's generic 4-option picker, not a PE deviation in the
  // wire model (DataSourceType itself is untouched).
  @Input()
  allowedDataSourceTypes?: DataSourceType[];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: DataSource) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.dsFormGroup = this.fb.group({
      type: [null],
      deviceId: [null],
      entityAliasId: [null],
      filterId: [null],
      dataKeys: [null],
      latestDataKeys: [null],
      alarmFilterConfig: [null]
    });
    this.dsFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(toDataSource(value, this.showAlarmFilter)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: DataSource) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.dsFormGroup.disable({emitEvent: false});
    } else {
      this.dsFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: DataSource): void {
    this.dsFormGroup.reset({
      type: value?.type ?? null,
      deviceId: value?.deviceId ?? null,
      entityAliasId: value?.entityAliasId ?? null,
      filterId: value?.filterId ?? null,
      dataKeys: value?.dataKeys ?? null,
      latestDataKeys: value?.latestDataKeys ?? null,
      alarmFilterConfig: value?.alarmFilterConfig ?? null
    }, {emitEvent: false});
  }

  get selectedType(): DataSourceType {
    return this.dsFormGroup?.get('type')?.value;
  }

  get isDevice(): boolean {
    return this.selectedType === DataSourceType.DEVICE;
  }

  get showEntityAliasSelect(): boolean {
    return isEntityAliasRelevant(this.selectedType);
  }

  get showFilterSelect(): boolean {
    return isFilterRelevant(this.selectedType);
  }

  get showDataKeys(): boolean {
    return isDataKeysRelevant(this.selectedType);
  }

  get showAlarmFilterControl(): boolean {
    return isAlarmFilterRelevant(this.selectedType, this.showAlarmFilter);
  }

  // Gates each `type` mat-option in the template. undefined allowedDataSourceTypes (the default)
  // permits every type, unchanged from before this Input existed.
  isTypeAllowed(type: DataSourceType): boolean {
    return !this.allowedDataSourceTypes || this.allowedDataSourceTypes.includes(type);
  }
}
