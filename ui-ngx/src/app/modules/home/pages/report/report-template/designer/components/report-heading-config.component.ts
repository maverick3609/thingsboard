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

import { Component, ElementRef, forwardRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { DataSource, HeadingComponent } from '@shared/models/report-configuration.models';
import { ReportAlignment } from '@home/pages/report/report-template/designer/widgets/report-alignment.component';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';
import { REPORT_DYNAMIC_FIELDS, ReportDynamicField } from '@home/pages/report/report-template/designer/report-dynamic-fields';

// CVA for components/HeadingComponent.java - the shell's right-panel content when the selected
// canvas component is a HEADING (report-template-editor.component.html). Mirrors T8's
// ReportDividerConfigComponent: the alignment pair and the 4 background/border layout fields are
// edited as single nested controls (matching tb-report-alignment/tb-report-background-border's own
// CVA value shapes) and flattened back onto HeadingComponent's top-level fields on the way out;
// toHeadingComponent() is that mapping step. Unlike DividerComponent, HEADING also extends
// ReportComponentDataSources - this C1 panel doesn't edit `dataSources`, so writeValue() stashes it
// and toHeadingComponent() re-attaches it untouched on every edit.
@Component({
    selector: 'tb-report-heading-config',
    templateUrl: './report-heading-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportHeadingConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportHeadingConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  // C2 Task 15B: the native <input> backing the `value` control (report-heading-config.component.html),
  // used by insertDynamicField() below to insert at the current cursor position via
  // selectionStart/selectionEnd + setRangeText. Stays undefined under this file's own
  // plain-instantiation spec convention (no TestBed => the template is never compiled), which is
  // exactly why insertDynamicField() has a DOM-free fallback.
  @ViewChild('valueInput')
  valueInput: ElementRef<HTMLInputElement>;

  readonly dynamicFields: ReportDynamicField[] = REPORT_DYNAMIC_FIELDS;

  headingFormGroup: UntypedFormGroup;

  disabled = false;

  private dataSources: DataSource[];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: HeadingComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.headingFormGroup = this.fb.group({
      value: [null],
      font: [null],
      color: [null],
      alignment: [{ textAlignment: null, verticalAlignment: null }],
      height: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.headingFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toHeadingComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: HeadingComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.headingFormGroup.disable({emitEvent: false});
    } else {
      this.headingFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(component: HeadingComponent): void {
    this.dataSources = component?.dataSources;
    this.headingFormGroup.reset({
      value: component?.value ?? null,
      font: component?.font ?? null,
      color: component?.color ?? null,
      alignment: {
        textAlignment: component?.textAlignment ?? null,
        verticalAlignment: component?.verticalAlignment ?? null
      },
      height: component?.height ?? null,
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

  // C2 Task 15B: inserts a dynamic-field token (e.g. '${pageNumber}') into the `value` control at the
  // native input's current cursor position (selectionStart/selectionEnd via setRangeText - the
  // standard DOM technique for cursor-aware text insertion; Angular's forms API has no equivalent of
  // its own), then re-reads the mutated DOM value back into the reactive form control so
  // valueChanges/propagateChange fire normally. Falls back to appending the token to the current
  // value when the input isn't resolvable (see valueInput's own comment above for when that happens).
  insertDynamicField(token: string): void {
    const control = this.headingFormGroup.get('value');
    const current: string = control.value ?? '';
    const inputEl = this.valueInput?.nativeElement;
    if (inputEl && typeof inputEl.setRangeText === 'function') {
      const start = inputEl.selectionStart ?? current.length;
      const end = inputEl.selectionEnd ?? current.length;
      inputEl.setRangeText(token, start, end, 'end');
      control.setValue(inputEl.value);
      inputEl.focus();
    } else {
      control.setValue(current + token);
    }
  }

  private toHeadingComponent(formValue: any): HeadingComponent {
    const alignment: ReportAlignment = formValue.alignment ?? {};
    const backgroundBorder: ReportBackgroundBorder = formValue.backgroundBorder ?? {};
    return {
      type: 'HEADING',
      value: formValue.value,
      font: formValue.font,
      color: formValue.color,
      textAlignment: alignment.textAlignment,
      verticalAlignment: alignment.verticalAlignment,
      height: formValue.height,
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
