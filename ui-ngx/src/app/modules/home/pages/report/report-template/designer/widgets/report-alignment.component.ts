// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, forwardRef, OnDestroy, OnInit } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, UntypedFormBuilder, UntypedFormGroup } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { TextAlignment, VerticalAlignment } from '@shared/models/report-configuration.models';

// The (textAlignment, verticalAlignment) pair is repeated verbatim across Heading/HeadingComponent
// /CellSettings in report-configuration.models.ts (T1); none of those factor it into a shared
// alignment type to Pick from, so this interface stays local to the widget.
export interface ReportAlignment {
  textAlignment?: TextAlignment;
  verticalAlignment?: VerticalAlignment;
}

// CVA for the (textAlignment, verticalAlignment) field pair. Mirrors the FormGroup ->
// propagateChange plumbing of kv-map.component.ts; the form's own value already has the
// ReportAlignment shape 1:1, so no mapping step is needed.
@Component({
    selector: 'tb-report-alignment',
    templateUrl: './report-alignment.component.html',
    styleUrls: [],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => ReportAlignmentComponent),
            multi: true
        }
    ],
    standalone: false
})
export class ReportAlignmentComponent implements ControlValueAccessor, OnInit, OnDestroy {

  alignmentFormGroup: UntypedFormGroup;

  disabled = false;

  // Enum refs for the template's [value] bindings (distinct names from the formControlNames below
  // to avoid a same-named form-control-vs-enum-object mixup in the markup).
  textAlignmentEnum = TextAlignment;
  verticalAlignmentEnum = VerticalAlignment;

  private destroy$ = new Subject<void>();
  private propagateChange: (value: ReportAlignment) => void = () => {};

  constructor(private fb: UntypedFormBuilder) {
  }

  ngOnInit(): void {
    this.alignmentFormGroup = this.fb.group({
      textAlignment: [null],
      verticalAlignment: [null]
    });
    this.alignmentFormGroup.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((value: ReportAlignment) => this.propagateChange(value));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  registerOnChange(fn: (value: ReportAlignment) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: any): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.disabled) {
      this.alignmentFormGroup.disable({emitEvent: false});
    } else {
      this.alignmentFormGroup.enable({emitEvent: false});
    }
  }

  writeValue(alignment: ReportAlignment): void {
    this.alignmentFormGroup.reset({
      textAlignment: alignment?.textAlignment ?? null,
      verticalAlignment: alignment?.verticalAlignment ?? null
    }, {emitEvent: false});
  }
}
