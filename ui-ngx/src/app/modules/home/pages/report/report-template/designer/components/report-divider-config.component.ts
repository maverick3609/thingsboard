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
import { BorderLength, BorderType, DividerComponent } from '@shared/models/report-configuration.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

// CVA for components/DividerComponent.java - the shell's right-panel content when the selected
// canvas component is a DIVIDER (report-template-editor.component.html). Mirrors the FormGroup ->
// propagateChange plumbing of T4's widgets/T5's page-settings panel, with one difference: unlike
// those, this form's own value isn't already 1:1 with DividerComponent - the 4
// background/border layout fields are edited as a single nested object (matching
// ReportBackgroundBorderComponent's own CVA value shape, composed the same way T5 composes
// tb-report-insets over its own opaque-object `pageMargins` control) and have to be flattened back
// onto DividerComponent's top-level background/borderWidth/borderRadius/borderColor fields
// (AbstractLayoutReportComponent.java) on the way out; toDividerComponent() is that mapping step.
// margins/paddings stay their own top-level Insets controls (optional, unlike PdfReportTemplateConfig
// .pageMargins) since tb-report-insets already matches Insets 1:1.
@Component({
    selector: 'tb-report-divider-config',
    templateUrl: './report-divider-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportDividerConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportDividerConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  dividerFormGroup: UntypedFormGroup;

  disabled = false;

  borderLengthEnum = BorderLength;
  borderTypeEnum = BorderType;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: DividerComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.dividerFormGroup = this.fb.group({
      length: [null],
      borderType: [null],
      widthPx: [null],
      color: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.dividerFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toDividerComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: DividerComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.dividerFormGroup.disable({emitEvent: false});
    } else {
      this.dividerFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: DividerComponent): void {
    this.dividerFormGroup.reset({
      length: value?.length ?? null,
      borderType: value?.borderType ?? null,
      widthPx: value?.widthPx ?? null,
      color: value?.color ?? null,
      margins: value?.margins ?? null,
      paddings: value?.paddings ?? null,
      backgroundBorder: {
        background: value?.background ?? null,
        borderWidth: value?.borderWidth ?? null,
        borderRadius: value?.borderRadius ?? null,
        borderColor: value?.borderColor ?? null
      }
    }, {emitEvent: false});
  }

  private toDividerComponent(value: any): DividerComponent {
    const backgroundBorder: ReportBackgroundBorder = value.backgroundBorder ?? {};
    return {
      type: 'DIVIDER',
      length: value.length,
      borderType: value.borderType,
      widthPx: value.widthPx,
      color: value.color,
      margins: value.margins,
      paddings: value.paddings,
      background: backgroundBorder.background,
      borderWidth: backgroundBorder.borderWidth,
      borderRadius: backgroundBorder.borderRadius,
      borderColor: backgroundBorder.borderColor
    };
  }
}
