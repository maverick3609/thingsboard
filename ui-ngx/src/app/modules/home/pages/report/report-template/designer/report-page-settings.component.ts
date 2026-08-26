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

import { Component, EventEmitter, Input, Output, forwardRef, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  PageOrientation,
  PageSize,
  PageSizeDimensions,
  PdfReportTemplateConfig
} from '@shared/models/report-configuration.models';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { dateFormats } from '@shared/models/widget-settings.models';

// The 6 page-level fields of configuration/PdfReportTemplateConfig.java this panel edits; the rest
// of the config (components, header/footer) is owned by the canvas column, not this panel.
// entityAliases/filters stay out of this Pick<> and out of the emitted form value too - they are
// edited from the toolbar's Aliases/Filters dialogs - so the shell's onPageSettingsChange
// (Object.assign(this.config, value)) can never clobber them.
export type ReportPageSettings = Pick<PdfReportTemplateConfig,
  'pageSize' | 'pageOrientation' | 'pageMargins' | 'pageBackground' | 'namePattern' | 'timeDataPattern'>;

// The template-settings pane PE shows on the right of the designer (docs/images/reporting-5.png,
// PE's tb-report-template-settings): a "Common settings" card over a "Page settings" card. Name and
// Description belong to the ReportTemplate ENTITY, not to the jsonb configuration, so they are
// edited through the plain `reportTemplate` Input below rather than through this CVA's value - two
// independent channels into two different objects, never merged.
//
// Shown by the shell whenever no canvas component is selected
// (report-template-editor.component.html, selected === null branch). pageSize/pageOrientation/
// pageMargins are non-optional in the Java model (like Insets' 4 fields), so writeValue defaults
// them rather than letting the form hold undefined; pageBackground/namePattern/timeDataPattern are
// genuinely optional and stay null when unset.
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

  // Plain Input (NOT part of the CVA's ReportPageSettings value): the entity-level name/description
  // rows below bind [(ngModel)] straight into it, the same way the toolbar's name field used to.
  @Input()
  reportTemplate: ReportTemplate;

  // Fired when a name/description edit mutated `reportTemplate` in place - the shell has no other
  // way to notice (the mutation happens through a reference it already holds) and needs it for the
  // toolbar's dirty state.
  @Output()
  templateChanged = new EventEmitter<void>();

  pageSettingsFormGroup: UntypedFormGroup;

  disabled = false;

  pageSizes = Object.values(PageSize);
  pageOrientationEnum = PageOrientation;

  // The platform's own preset list (the one tb-date-format-select offers), minus the entries that
  // are not plain patterns. `timeDataPattern` is a free-text Java date pattern, so these are
  // autocomplete SUGGESTIONS over a text input, never a closed mat-select: a template whose pattern
  // isn't on this list must not silently lose it.
  dateFormatPatterns = dateFormats.filter(format => !format.lastUpdateAgo && !format.custom && !format.auto)
    .map(format => format.format);

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

  // A subreport is embedded in its parent's page, so it has neither a file name of its own nor page
  // geometry of its own - PE disables exactly these controls for one; we leave them out entirely.
  // The underlying form controls keep whatever the config already held, so hiding the rows never
  // rewrites them.
  get isSubReport(): boolean {
    return this.reportTemplate?.type === ReportTemplateType.SUB_REPORT;
  }

  // Page geometry only exists on PdfReportTemplateConfig - CsvReportTemplateConfig has none of
  // these fields, per the frozen model.
  get showPageSettings(): boolean {
    return !this.isSubReport && this.reportTemplate?.format === TbReportFormat.PDF;
  }
}
