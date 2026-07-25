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
import { DataSource, RichTextComponent } from '@shared/models/report-configuration.models';
import { ReportBackgroundBorder } from '@home/pages/report/report-template/designer/widgets/report-background-border.component';

// CVA for components/RichTextComponent.java - the shell's right-panel content when the selected
// canvas component is a RICH_TEXT (report-template-editor.component.html). Mirrors T9's
// ReportHeadingConfigComponent: the 4 background/border layout fields are edited as a single
// nested control (matching tb-report-background-border's own CVA value shape) and flattened back
// onto RichTextComponent's top-level fields on the way out; toRichTextComponent() is that mapping
// step. Simpler than HEADING - just `value` (HTML, edited via the platform's tb-html ace editor)
// plus layout, no font/color/alignment/height. Like HEADING, RICH_TEXT also extends
// ReportComponentDataSources - this C1 panel doesn't edit `dataSources`, so writeValue() stashes it
// and toRichTextComponent() re-attaches it untouched on every edit.
@Component({
    selector: 'tb-report-rich-text-config',
    templateUrl: './report-rich-text-config.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportRichTextConfigComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportRichTextConfigComponent implements ControlValueAccessor, OnInit, OnDestroy {

  richTextFormGroup: UntypedFormGroup;

  disabled = false;

  private dataSources: DataSource[];

  private destroy$ = new Subject<void>();
  private propagateChange: (value: RichTextComponent) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.richTextFormGroup = this.fb.group({
      value: [null],
      margins: [null],
      paddings: [null],
      backgroundBorder: [{ background: null, borderWidth: null, borderRadius: null, borderColor: null }]
    });
    this.richTextFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: any) => this.propagateChange(this.toRichTextComponent(value)));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: RichTextComponent) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.richTextFormGroup.disable({emitEvent: false});
    } else {
      this.richTextFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(component: RichTextComponent): void {
    this.dataSources = component?.dataSources;
    this.richTextFormGroup.reset({
      value: component?.value ?? null,
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

  private toRichTextComponent(formValue: any): RichTextComponent {
    const backgroundBorder: ReportBackgroundBorder = formValue.backgroundBorder ?? {};
    return {
      type: 'RICH_TEXT',
      value: formValue.value,
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
