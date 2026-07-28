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
  DataSourceType,
  ImageAlignment,
  ImageComponent,
  ImageSourceType,
  ImageWidthType
} from '@shared/models/report-configuration.models';
import { IAliasController } from '@core/api/widget-api.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

// CVA for components/ImageComponent.java - the shell's right-panel content when the selected
// canvas component is an IMAGE (report-template-editor.component.html). Mirrors T9's
// ReportHeadingConfigComponent/T10's ReportRichTextConfigComponent: the 4 background/border layout
// fields are edited as a single nested control (matching tb-report-background-border's own CVA
// value shape) and flattened back onto ImageComponent's top-level fields on the way out;
// toImageComponent() is that mapping step.
//
// sourceType (components/ImageSourceType.java) selects between the 2 image sources this panel
// edits (Task IMG - the C1 gap fix; C1 only wired IMAGE):
//  - IMAGE: a static image referenced by imageUrl, picked/uploaded via the platform's
//    tb-gallery-image-input (which already opens the image gallery dialog itself - a bespoke
//    picker would duplicate that machinery).
//  - ENTITY_KEY: a data-driven image - ImageRenderer.java's getImageUrl() reads
//    dataSources[0].dataKeys[0]'s resolved value as the image URI (task-img-interfaces.md's
//    "Backend ground truth"; unbound -> empty -> graceful placeholder). Edited via a SINGLE
//    tb-report-datasource (Task 8's CVA - the same "one opaque nested CVA control" composition
//    Task 11 uses for ALARM_TABLE's alarmSource) bound to a `dataSource` form control, narrowed to
//    device/entity via Task 11's allowedDataSourceTypes gating (ENTITY_COUNT/ALARM_COUNT are
//    scalar-count shapes with no per-row value to read as a URI).
//
// dataSource <-> dataSources[0] mapping: writeValue() seeds `dataSource` from
// `component.dataSources?.[0]`, defaulting to a fresh empty device-type datasource
// ({type: DataSourceType.DEVICE, dataKeys: []}) when absent - the blank starting shape
// tb-report-datasource expects. toImageComponent() emits `dataSources: [<dataSource control
// value>]` UNCONDITIONALLY (not gated on sourceType) - ImageComponent is single-source (the
// renderer only ever reads index 0), so a loaded config with more than one entry collapses to 1
// after the first edit - the accepted PE-faithful narrowing task-img-interfaces.md calls out.
//
// ROUND-TRIP FIDELITY ACROSS sourceType SWITCHES: both `imageUrl` and `dataSources` are ALWAYS
// present in the emitted component regardless of the current sourceType - switching the mat-select
// only changes which editor is VISIBLE (isImageSource/isEntityKeySource below gate the template),
// it never wipes the other source's stored value. The renderer picks which one it actually reads
// by sourceType, so keeping both around is inert on the inactive side and lets a user flip back and
// forth without losing work.
//
// alignment here is ImageAlignment (left/center/right - components/ImageAlignment.java), a
// DIFFERENT enum from the (textAlignment, verticalAlignment) pair tb-report-alignment edits, so it
// gets its own dedicated select rather than reusing that widget.
@Component({
    selector: 'tb-report-image-config',
    templateUrl: './report-image-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportImageConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportImageConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  imageFormGroup: UntypedFormGroup;

  disabled = false;

  sourceTypeEnum = ImageSourceType;
  widthTypeEnum = ImageWidthType;
  alignmentEnum = ImageAlignment;

  // Forwarded to tb-report-datasource for the ENTITY_KEY source's dataSource control - see Task 9's
  // createReportAliasController (created once by the shell in ngOnInit, threaded down here exactly
  // like Task 11/13/14's panels).
  @Input()
  aliasController: IAliasController;

  // Task 11 carry: an ENTITY_KEY image only makes sense against a device/entity datasource -
  // ENTITY_COUNT/ALARM_COUNT are scalar-count shapes with no per-row value to read as an image URI.
  readonly imageDataSourceTypes: DataSourceType[] = [DataSourceType.DEVICE, DataSourceType.ENTITY];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ImageComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.imageFormGroup = this.fb.group({
      sourceType: [ImageSourceType.IMAGE],
      imageUrl: [null],
      dataSource: [null],
      widthType: [null],
      customWidth: [null],
      alignment: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.imageFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toImageComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ImageComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.imageFormGroup.disable({emitEvent: false});
    } else {
      this.imageFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(component: ImageComponent): void {
    this.imageFormGroup.reset({
      sourceType: component?.sourceType ?? ImageSourceType.IMAGE,
      imageUrl: component?.imageUrl ?? null,
      dataSource: component?.dataSources?.[0] ?? { type: DataSourceType.DEVICE, dataKeys: [] },
      widthType: component?.widthType ?? null,
      customWidth: component?.customWidth ?? null,
      alignment: component?.alignment ?? null,
      margins: component?.margins ?? null,
      paddings: component?.paddings ?? null,
      backgroundBorder: {
        background: component?.background ?? null,
        borderWidth: component?.borderWidth ?? null,
        borderRadius: component?.borderRadius ?? null,
        borderColor: component?.borderColor ?? null
      }
    }, {emitEvent: false});
  }

  get isImageSource(): boolean {
    return this.imageFormGroup?.get('sourceType')?.value === ImageSourceType.IMAGE;
  }

  get isEntityKeySource(): boolean {
    return this.imageFormGroup?.get('sourceType')?.value === ImageSourceType.ENTITY_KEY;
  }

  private toImageComponent(formValue: any): ImageComponent {
    const backgroundBorder: ReportBackgroundBorder = formValue.backgroundBorder ?? {};
    return {
      type: 'IMAGE',
      sourceType: formValue.sourceType,
      imageUrl: formValue.imageUrl,
      dataSources: [formValue.dataSource],
      widthType: formValue.widthType,
      customWidth: formValue.customWidth ?? undefined,
      alignment: formValue.alignment,
      margins: formValue.margins,
      paddings: formValue.paddings,
      background: backgroundBorder.background,
      borderWidth: backgroundBorder.borderWidth,
      borderRadius: backgroundBorder.borderRadius,
      borderColor: backgroundBorder.borderColor
    };
  }
}
