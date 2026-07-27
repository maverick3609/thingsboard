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
import { isDefinedAndNotNull } from '@core/utils';
import {
  DataKey,
  DataKeySettings,
  DataSourceType,
  TimeWindowConfiguration
} from '@shared/models/report-configuration.models';
import { DataKey as PlatformDataKey, DatasourceType, widgetType } from '@shared/models/widget.models';
import { DataKeyType } from '@shared/models/telemetry/telemetry.models';
import { IAliasController } from '@core/api/widget-api.models';
import { WidgetConfigCallbacks } from '@home/components/widget/config/widget-config.component.models';

// CVA for DataKey.java[] (report-configuration.models.ts:238-249). Hosts the platform's tb-data-keys
// widget (modules/home/components/widget/lib/settings/common/key/data-keys.component.ts) for the
// base fields (name/type/label/color/units/decimals/aggregationType - its chip list + "edit"
// pencil-icon dialog), and this component's OWN per-key expansion panel (below the chip list) for
// the report-only ColumnSettings sub-form (T3's tb-report-column-settings). Consumed by
// report-datasource (Task 8) for DataSource.dataKeys/latestDataKeys and ALARM_TABLE's alarmSource
// panel (Task 11).
//
// ## Why NOT tb-data-keys' own dataKeySettingsForm/dataKeySettingsDirective slot
// tb-data-keys exposes a native "advanced settings" extension point (dataKeySettingsForm:
// FormProperty[] / dataKeySettingsDirective: string), surfaced inside its pencil-icon edit dialog
// (DataKeyConfigDialogComponent -> DataKeyConfigComponent's "Advanced" tab). Traced both all the way
// through (data-key-config.component.html:196-207): the advanced tab always renders
// `<tb-widget-settings [dashboard] [aliasController] [callbacks] [widget] [widgetConfig]
// formControlName="settings">` - i.e. dataKeySettingsForm feeds a JSON-schema dynamic-form renderer,
// dataKeySettingsDirective a directive-selector dynamically instantiated by that SAME
// tb-widget-settings host - and either path requires a live `widget`/`dashboard`/`widgetConfig`
// object, full widget-editing context the report designer fundamentally doesn't have (c2-reuse-cva-
// notes.md's tb-data-keys section already flags aliasController/callbacks as the integration gap;
// tb-widget-settings needs MORE than that). Using it would ALSO mean re-authoring T3's already-built
// ReportColumnSettingsComponent as a FormProperty[] JSON schema instead of the typed Angular CVA it
// already is, and burying the settings UI inside tb-data-keys' own modal dialog chrome instead of
// this designer's own right-panel. So: dataKeySettingsForm/dataKeySettingsDirective are left unset
// below (tb-data-keys' own `hasAdvanced` stays false), and ColumnSettings is edited by OUR OWN
// per-key panel instead - matching the task brief's own prescribed Step 3 design.
//
// ## How report-only fields (settings, timewindow) survive tb-data-keys' internal mutations
// tb-data-keys freely add/remove/reorder(drag)/color-edit/pencil-edit-dialog the array it's bound
// to, but NONE of those paths enumerate or strip unrecognized object keys - they either move the
// SAME element reference (remove/reorder/color edit - see data-keys.component.ts's remove()/
// onChipDrop()/openColorPickerPopup()) or spread-merge only their OWN known fields on top of a deep
// clone of the original (the pencil dialog's DataKeyConfigComponent.updateModel():
// `{...this.modelValue, ...this.dataKeyFormGroup.value}`, where dataKeyFormGroup never declares a
// `settings` or `timewindow` control when hasAdvanced is false - confirmed by reading
// data-key-config.component.ts's ngOnInit/writeValue/updateModel line by line). So this component
// carries the report's `settings` value through the platform DataKey's OWN pre-existing
// `settings?: any` extension field (its intended purpose - a per-key opaque payload slot, just not
// routed through tb-data-keys' OWN advanced-tab machinery), and `timewindow` (a report-only field
// with NO platform slot at all) through one extra property on a local carrier type. Both ride along
// untouched through every tb-data-keys-internal mutation because they attach to the object BY
// REFERENCE, not by array position - the only approach that survives a live drag-reorder without a
// fragile index-diff against a stale previous array.
//
// ## Known limitation: postFuncBody/units object forms are dropped, not preserved
// tb-data-keys' pencil-icon dialog binds `<tb-js-func withModules>` UNCONDITIONALLY to postFuncBody
// (data-key-config.component.html:175-183 - no @Input exists to suppress `withModules`), so a user
// can attach ES-module imports to a post-processing function, producing a TbFunctionWithModules
// object (`{body, modules}`) rather than a plain string. `showPostProcessing` (which gates whether
// that whole panel is even shown) defaults true for every widgetType except `alarm`
// (data-keys.component.ts's editDataKey(): `showPostProcessing: this.widgetType !== widgetType.
// alarm`), and this component's OWN widgetType default is `latest` - so this path is reachable out
// of the box, not just theoretically. Symmetrically, tb-unit-input can in principle emit a
// TbUnitMapping object for `units` rather than a plain string. The report's postFuncBody is TBEL,
// which has no ES-module concept, and units is plain-string-only - there is no lossless
// representation to fall back to, and the frozen report model is NOT widened to invent one. So
// asReportString() below correctly DROPS both non-string forms, but console.warn()s naming the
// field and the key first, so the loss is diagnosable at the point it happens instead of silent.
export type PlatformDataKeyCarrier = PlatformDataKey & { timewindow?: TimeWindowConfiguration };

// Drops a non-string value, warning (rather than silently discarding) when the dropped value was
// actually present - see "Known limitation" above. `fieldName`/`keyName` make the warning
// actionable (which key, which field) instead of a bare "something was dropped somewhere".
function asReportString(value: unknown, fieldName: 'units' | 'postFuncBody', keyName: string): string | undefined {
  if (typeof value === 'string') {
    return value;
  }
  if (isDefinedAndNotNull(value)) {
    console.warn(
      `tb-report-data-keys: dropping non-string ${fieldName} for data key "${keyName ?? '(unnamed)'}" - ` +
      `report post-processing/units are plain-string only; the object/module form is not supported ` +
      `and has been discarded.`
    );
  }
  return undefined;
}

// pure mapping functions - exported standalone so a mapping bug is directly unit-testable without
// the Angular CVA/DOM machinery (mirrors report-timewindow.component.ts's reportToPlatformTimewindow
// / platformToReportTimewindow). Field by field, see task-7-report.md's mapping table; in short:
// name/label/decimals/aggregationType/usePostProcessing/settings/timewindow copy straight through
// (aggregationType is literally the same AggregationType enum on both sides - report-configuration.
// models.ts imports it from time.models.ts, same as widget.models.ts does); type is a same-value
// string<->enum hop (string->enum needs a cast, enum->string doesn't); units/postFuncBody are
// narrowed to plain strings on the way back to the report shape since the platform's TbUnit/
// TbFunction are wider unions (`string | TbUnitMapping` / `string | TbFunctionWithModules`) the
// report's string-only fields have no slot for (dropped with a console.warn - see "Known
// limitation" above, NOT silently). Platform-only fields (color, pattern, hidden, inLegend,
// isAdditional, origDataKeyIndex, comparisonEnabled/timeForComparison/comparisonCustomIntervalValue/
// comparisonResultType, funcBody) are never copied to the report shape.
// Every optional field is isDefinedAndNotNull-guarded (matching report-timewindow.component.ts's/
// report-alarm-filter.component.ts's convention) so an absent field stays absent rather than
// materializing an explicit `field: undefined` key - both because that's what a real backend jsonb
// payload looks like, and because Jasmine's `toEqual` in this project does NOT treat an
// undefined-valued key as equivalent to an absent one (confirmed by running the round-trip spec).
export function reportToPlatformDataKeys(keys: DataKey[]): PlatformDataKeyCarrier[] {
  return (keys ?? []).map(key => {
    const platform: PlatformDataKeyCarrier = {
      name: key.name,
      type: key.type as DataKeyType
    };
    if (isDefinedAndNotNull(key.label)) {
      platform.label = key.label;
    }
    if (isDefinedAndNotNull(key.decimals)) {
      platform.decimals = key.decimals;
    }
    if (isDefinedAndNotNull(key.units)) {
      platform.units = key.units;
    }
    if (isDefinedAndNotNull(key.aggregationType)) {
      platform.aggregationType = key.aggregationType;
    }
    if (isDefinedAndNotNull(key.usePostProcessing)) {
      platform.usePostProcessing = key.usePostProcessing;
    }
    if (isDefinedAndNotNull(key.postFuncBody)) {
      platform.postFuncBody = key.postFuncBody;
    }
    if (isDefinedAndNotNull(key.settings)) {
      platform.settings = key.settings;
    }
    if (isDefinedAndNotNull(key.timewindow)) {
      platform.timewindow = key.timewindow;
    }
    return platform;
  });
}

export function platformToReportDataKeys(keys: PlatformDataKeyCarrier[]): DataKey[] {
  return (keys ?? []).map(key => {
    const report: DataKey = {
      name: key.name,
      type: key.type
    };
    if (isDefinedAndNotNull(key.label)) {
      report.label = key.label;
    }
    if (isDefinedAndNotNull(key.decimals)) {
      report.decimals = key.decimals;
    }
    const units = asReportString(key.units, 'units', key.name);
    if (isDefinedAndNotNull(units)) {
      report.units = units;
    }
    if (isDefinedAndNotNull(key.aggregationType)) {
      report.aggregationType = key.aggregationType;
    }
    if (isDefinedAndNotNull(key.usePostProcessing)) {
      report.usePostProcessing = key.usePostProcessing;
    }
    const postFuncBody = asReportString(key.postFuncBody, 'postFuncBody', key.name);
    if (isDefinedAndNotNull(postFuncBody)) {
      report.postFuncBody = postFuncBody;
    }
    if (isDefinedAndNotNull(key.settings)) {
      report.settings = key.settings as DataKeySettings;
    }
    if (isDefinedAndNotNull(key.timewindow)) {
      report.timewindow = key.timewindow;
    }
    return report;
  });
}

// Explicit member-by-member map rather than an `as unknown as DatasourceType` cast: report
// DataSourceType's 4 members (device/entity/entityCount/alarmCount) are a same-value subset of
// platform DatasourceType's 5 (which adds `function`, not applicable to a report data source). A
// `Record` keyed by every DataSourceType member means a future addition to the report enum fails to
// compile here until mapped, instead of silently casting through unchanged.
const REPORT_TO_PLATFORM_DATASOURCE_TYPE: Record<DataSourceType, DatasourceType> = {
  [DataSourceType.DEVICE]: DatasourceType.device,
  [DataSourceType.ENTITY]: DatasourceType.entity,
  [DataSourceType.ENTITY_COUNT]: DatasourceType.entityCount,
  [DataSourceType.ALARM_COUNT]: DatasourceType.alarmCount
};

@Component({
    selector: 'tb-report-data-keys',
    templateUrl: './report-data-keys.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportDataKeysComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportDataKeysComponent implements ControlValueAccessor, OnInit, OnDestroy {

  dataKeysFormGroup: UntypedFormGroup;

  modelValue: DataKey[] = [];

  disabled = false;

  // Report's own DataSourceType (device/entity/entityCount/alarmCount, no `function` member) -
  // mapped to the platform's DatasourceType for tb-data-keys via the platformDatasourceType getter
  // below. Wired by Task 8 (dataSources[]) / Task 11 (alarmSource).
  @Input()
  dataSourceType: DataSourceType;

  // REQUIRED by tb-data-keys (drives its placeholder text + reset()/getDefaultAlarmDataKeys()
  // behavior) but genuinely consumer-specific - not knowable inside this generic wrapper. Defaults
  // to `latest` (closest match for a static report snapshot table); Task 8/11 can override per
  // component type (e.g. `alarm` for ALARM_TABLE's alarmSource, `timeseries` for TIME_SERIES_TABLE).
  @Input()
  widgetType: widgetType = widgetType.latest;

  // Pass-through only - NOT wired by this task (c2-reuse-cva-notes.md's tb-data-keys integration
  // gap; REORDER note in progress.md: Task 9 builds the report-specific IAliasController adapter).
  @Input()
  aliasController: IAliasController;

  @Input()
  callbacks: WidgetConfigCallbacks;

  @Input()
  entityAliasId: string;

  @Input()
  deviceId: string;

  get platformDatasourceType(): DatasourceType {
    return REPORT_TO_PLATFORM_DATASOURCE_TYPE[this.dataSourceType];
  }

  private destroy$ = new Subject<void>();
  private propagateChange: (value: DataKey[]) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.dataKeysFormGroup = this.fb.group({
      keys: [[] as PlatformDataKeyCarrier[]]
    });
    this.dataKeysFormGroup.get('keys').valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((keys: PlatformDataKeyCarrier[]) => {
      this.modelValue = platformToReportDataKeys(keys);
      this.propagateChange(this.modelValue);
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: DataKey[]) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.dataKeysFormGroup.disable({emitEvent: false});
    } else {
      this.dataKeysFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: DataKey[]): void {
    this.modelValue = value ?? [];
    this.dataKeysFormGroup.reset({
      keys: reportToPlatformDataKeys(this.modelValue)
    }, {emitEvent: false});
  }

  trackByIndex(index: number, _key: DataKey): number {
    return index;
  }

  // Invoked by this component's OWN per-key tb-report-column-settings panel (NOT by tb-data-keys).
  // Updates the report-shape modelValue (what we emit) AND pushes the recomputed platform-shape
  // array back into the `keys` control with {emitEvent:false} - re-syncing tb-data-keys' internal
  // state so a LATER, unrelated tb-data-keys-originated change (e.g. editing a different key's
  // label) reads the freshly-set `settings` back off the carrier rather than a stale copy.
  // isDefinedAndNotNull-guarded (matching reportToPlatformDataKeys/platformToReportDataKeys'
  // construction discipline above): a falsy `settings` REMOVES the key's `settings` property
  // entirely rather than setting it to an explicit `undefined`, which a bare `{...key, settings}`
  // spread would otherwise do (object-literal spread always materializes the key, even when the
  // value being spread in is `undefined`).
  onColumnSettingsChange(index: number, settings: DataKeySettings): void {
    this.modelValue = this.modelValue.map((key, i) => {
      if (i !== index) {
        return key;
      }
      const updatedKey = { ...key };
      if (isDefinedAndNotNull(settings)) {
        updatedKey.settings = settings;
      } else {
        delete updatedKey.settings;
      }
      return updatedKey;
    });
    this.dataKeysFormGroup.get('keys').setValue(reportToPlatformDataKeys(this.modelValue), {emitEvent: false});
    this.propagateChange(this.modelValue);
  }
}
