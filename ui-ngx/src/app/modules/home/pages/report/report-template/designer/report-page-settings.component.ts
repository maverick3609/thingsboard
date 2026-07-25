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
import { PageOrientation, PageSize, PageSizeDimensions, PdfReportTemplateConfig } from '@shared/models/report-configuration.models';

// The 6 page-level fields of configuration/PdfReportTemplateConfig.java this panel edits; the rest
// of the config (components, entityAliases, filters, header/footer) is owned by the canvas (T6) and
// header/footer editors, not this panel.
export type ReportPageSettings = Pick<PdfReportTemplateConfig,
  'pageSize' | 'pageOrientation' | 'pageMargins' | 'pageBackground' | 'namePattern' | 'timeDataPattern'>;

// CVA for the PDF root's page-level fields - the shell's right-panel content when no canvas
// component is selected (report-template-editor.component.html, selected === null branch). Mirrors
// the FormGroup -> propagateChange plumbing of kv-map.component.ts / T4's widgets; the form's own
// value already has the ReportPageSettings shape 1:1, so no mapping step is needed. pageSize/
// pageOrientation/pageMargins are non-optional in the Java model (like Insets' 4 fields), so
// writeValue defaults them rather than letting the form hold undefined; pageBackground/namePattern/
// timeDataPattern are genuinely optional and stay null when unset.
@Component({
    selector: 'tb-report-page-settings',
    templateUrl: './report-page-settings.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportPageSettingsComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportPageSettingsComponent implements ControlValueAccessor, OnInit, OnDestroy {

  pageSettingsFormGroup: UntypedFormGroup;

  disabled = false;

  pageSizes = Object.values(PageSize);
  pageOrientationEnum = PageOrientation;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportPageSettings) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.pageSettingsFormGroup = this.fb.group({
      pageSize: [PageSize.A4],
      pageOrientation: [PageOrientation.PORTRAIT],
      pageMargins: [{ left: 0, right: 0, top: 0, bottom: 0 }],
      pageBackground: [null],
      namePattern: [null],
      timeDataPattern: [null]
    });
    this.pageSettingsFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: ReportPageSettings) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportPageSettings) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.pageSettingsFormGroup.disable({emitEvent: false});
    } else {
      this.pageSettingsFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: ReportPageSettings): void {
    this.pageSettingsFormGroup.reset({
      pageSize: value?.pageSize ?? PageSize.A4,
      pageOrientation: value?.pageOrientation ?? PageOrientation.PORTRAIT,
      pageMargins: value?.pageMargins ?? { left: 0, right: 0, top: 0, bottom: 0 },
      pageBackground: value?.pageBackground ?? null,
      namePattern: value?.namePattern ?? null,
      timeDataPattern: value?.timeDataPattern ?? null
    }, {emitEvent: false});
  }

  // Drives both the mat-select's per-option label ("A4 (595×842)") and the field's dimension hint
  // for whichever size is currently selected - a single PageSizeDimensions lookup for both surfaces.
  dimensionsLabel(size: PageSize): string {
    const dims = size ? PageSizeDimensions[size] : null;
    return dims ? `${dims.width}×${dims.height}` : '';
  }
}
