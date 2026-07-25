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
  DataSource,
  ImageAlignment,
  ImageComponent,
  ImageSourceType,
  ImageWidthType
} from '@shared/models/report-configuration.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

// CVA for components/ImageComponent.java - the shell's right-panel content when the selected
// canvas component is an IMAGE (report-template-editor.component.html). Mirrors T9's
// ReportHeadingConfigComponent/T10's ReportRichTextConfigComponent: the 4 background/border layout
// fields are edited as a single nested control (matching tb-report-background-border's own CVA
// value shape) and flattened back onto ImageComponent's top-level fields on the way out;
// toImageComponent() is that mapping step. Like HEADING/RICH_TEXT, IMAGE also extends
// ReportComponentDataSources - this C1 panel doesn't edit `dataSources`, so writeValue() stashes it
// and toImageComponent() re-attaches it untouched on every edit.
//
// sourceType (components/ImageSourceType.java) is REQUIRED on the model, but this C1 panel only
// ever writes ImageSourceType.IMAGE - a static image referenced by imageUrl, picked/uploaded via
// the platform's tb-gallery-image-input (which already opens the image gallery dialog itself - a
// bespoke picker would duplicate that machinery). ENTITY_KEY (data-driven image) is deferred to
// C2, so the select below offers only the one option for now.
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

  private dataSources: DataSource[];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ImageComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.imageFormGroup = this.fb.group({
      sourceType: [ImageSourceType.IMAGE],
      imageUrl: [null],
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
    this.dataSources = component?.dataSources;
    this.imageFormGroup.reset({
      sourceType: component?.sourceType ?? ImageSourceType.IMAGE,
      imageUrl: component?.imageUrl ?? null,
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

  private toImageComponent(formValue: any): ImageComponent {
    const backgroundBorder: ReportBackgroundBorder = formValue.backgroundBorder ?? {};
    return {
      type: 'IMAGE',
      sourceType: formValue.sourceType,
      imageUrl: formValue.imageUrl,
      widthType: formValue.widthType,
      customWidth: formValue.customWidth ?? undefined,
      alignment: formValue.alignment,
      margins: formValue.margins,
      paddings: formValue.paddings,
      background: backgroundBorder.background,
      borderWidth: backgroundBorder.borderWidth,
      borderRadius: backgroundBorder.borderRadius,
      borderColor: backgroundBorder.borderColor,
      dataSources: this.dataSources
    };
  }
}
