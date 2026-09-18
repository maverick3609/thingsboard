// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { bitsToFloat, ControllerConfigSection, controllerRecordError, ControllerSettingField,
  FLOAT_DATA_FORMATS, floatToBits, LOCAL_POINT_SOURCES, NO_SCALING, refOptionLabel,
  RTU_POINT_SOURCE } from '@shared/models/inferrix-controller.models';

export interface ControllerConfigRecordDialogData {
  deviceId: string;
  section: ControllerConfigSection;
  /** Absent when adding. */
  record?: any;
  /** Records of the section this one references, for the id pickers. Null if that read failed. */
  refRecords?: any[];
  /** Keys already used in this section, so a picker cannot offer one that is taken. */
  takenKeys?: number[];
}

/**
 * One config record, built from its section's field spec.
 *
 * Writes are always **full records** — the device has no partial edit — so an add and an edit are
 * the same request, and the only difference here is that the key field is frozen once a record
 * exists: changing it would leave the original behind and append a second record.
 */
@Component({
  selector: 'tb-controller-config-record-dialog',
  templateUrl: './controller-config-record-dialog.component.html',
  styleUrls: ['./controller-config-record-dialog.component.scss'],
  standalone: false
})
export class ControllerConfigRecordDialogComponent
  extends DialogComponent<ControllerConfigRecordDialogComponent, boolean> {

  readonly section: ControllerConfigSection;
  readonly isAdd: boolean;

  recordForm: UntypedFormGroup;
  errorMessage: string = null;

  private refOptionsByKey: {[key: string]: {value: any; label: string}[]} = {};

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ControllerConfigRecordDialogData,
              public dialogRef: MatDialogRef<ControllerConfigRecordDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private controllerService: InferrixControllerService) {
    super(store, router, dialogRef);
    this.section = data.section;
    this.isAdd = !data.record;
    const record = data.record ?? {};
    this.recordForm = this.fb.group(Object.fromEntries(this.section.fields.map(field =>
      [field.key, [this.initialValue(field, record), this.validatorsFor(field)]])));
    if (!this.isAdd) {
      this.recordForm.get(this.section.idField)?.disable();
    }
    if (this.isPointSection) {
      const source = this.recordForm.get('source');
      this.applySourceRules(source.value);
      source.valueChanges.subscribe(value => {
        this.applySourceRules(value);
        this.rebuildRefOptions();
      });
    }
    this.rebuildRefOptions();
  }

  get isPointSection(): boolean {
    return this.section.key === 'points';
  }

  /**
   * The picker for a field that holds another section's key, or null to type the id as before.
   *
   * A query is only named by an RTU point — every other source reads `source_ref` as something else
   * entirely (a local channel index, a peer id), so the picker would be lying. A policy may name any
   * point that has no policy yet; the device allows at most one each, and offering a taken point
   * would just move the rejection to Apply.
   */
  refOptions(field: ControllerSettingField): {value: any; label: string}[] {
    return this.refOptionsByKey[field.key] ?? null;
  }

  /**
   * Builds the pickers once per source change, not per change-detection pass: a points section holds
   * up to 1024 records, and rebuilding that list in a template expression would rebuild it on every
   * keystroke in the dialog.
   */
  private rebuildRefOptions(): void {
    this.refOptionsByKey = {};
    this.section.fields.filter(field => field.optionsFrom).forEach(field => {
      if (!this.data.refRecords) {
        return;
      }
      if (field.optionsFrom === 'queries'
          && this.recordForm.get('source')?.value !== RTU_POINT_SOURCE) {
        return;
      }
      const idKey = field.optionsFrom === 'queries' ? 'query_id' : 'point_id';
      const own = Number(this.data.record?.[field.key]);
      const taken = field.optionsFrom === 'points' ? this.data.takenKeys ?? [] : [];
      this.refOptionsByKey[field.key] = this.data.refRecords
        .filter(record => Number(record[idKey]) === own || !taken.includes(Number(record[idKey])))
        .map(record => ({value: Number(record[idKey]), label: refOptionLabel(field.optionsFrom, record)}));
    });
  }

  /** A local point is offered no float format: the controller refuses one at apply (firmware 0.1.16). */
  optionsFor(field: ControllerSettingField): {value: any; label: string}[] {
    return field.key === 'data_format' && this.isLocalSource()
      ? field.options.filter(option => !FLOAT_DATA_FORMATS.includes(option.value))
      : field.options;
  }

  /**
   * The warning for opening a local output to remote writes, or null. A program can drive the same
   * output, and the two then fight over it.
   */
  get writableOutputWarning(): string {
    if (!this.isPointSection || ((this.recordForm.get('flags').value | 0) & 1) === 0) {
      return null;
    }
    switch (this.recordForm.get('source').value) {
      case 1:
        return 'inferrix.writable-do-warning';
      case 3:
        return 'inferrix.writable-ao-warning';
      default:
        return null;
    }
  }

  /** Checkbox state for one named bit of a bitmask field. */
  hasBit(field: ControllerSettingField, bit: number): boolean {
    return ((this.recordForm.get(field.key).value | 0) & bit) !== 0;
  }

  setBit(field: ControllerSettingField, bit: number, on: boolean): void {
    const current = this.recordForm.get(field.key).value | 0;
    this.recordForm.get(field.key).setValue(on ? current | bit : current & ~bit);
    this.recordForm.markAsDirty();
  }

  save(): void {
    if (this.recordForm.invalid) {
      this.recordForm.markAllAsTouched();
      return;
    }
    this.errorMessage = null;
    this.controllerService.upsertConfigRecord(this.data.deviceId, this.section, this.payload())
      .subscribe({
        next: () => this.dialogRef.close(true),
        error: error => this.errorMessage = controllerRecordError(error,
            error?.error?.message || error?.message || 'Save failed')
      });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }

  private payload(): {[key: string]: any} {
    // getRawValue, not value: the key field is disabled while editing and still has to be sent.
    const value = this.recordForm.getRawValue();
    const record: {[key: string]: any} = {};
    this.section.fields.forEach(field => {
      const current = value[field.key];
      record[field.key] = field.float32Bits ? floatToBits(Number(current)) : current;
    });
    return record;
  }

  private isLocalSource(): boolean {
    return this.isPointSection && LOCAL_POINT_SOURCES.includes(this.recordForm.get('source').value);
  }

  /**
   * What the controller refuses for a local source at apply (ICC_BAD_LOCAL_POINT): a scaling on a
   * DI or DO, and a float format on any of them. Refused here instead, so the operator finds out
   * while editing the record rather than when the whole draft is rejected.
   */
  private applySourceRules(source: number): void {
    const scaling = this.recordForm.get('scaling_idx');
    if (source === 0 || source === 1) {
      scaling.setValue(NO_SCALING);
      scaling.disable();
    } else {
      scaling.enable();
    }
    const format = this.recordForm.get('data_format');
    if (this.isLocalSource() && FLOAT_DATA_FORMATS.includes(format.value)) {
      format.setValue(null);
    }
  }

  private initialValue(field: ControllerSettingField, record: any): any {
    const current = record[field.key];
    if (current === undefined || current === null) {
      return field.defaultValue ?? null;
    }
    return field.float32Bits ? bitsToFloat(Number(current)) : current;
  }

  private validatorsFor(field: ControllerSettingField): any[] {
    const validators = [];
    if (field.required) {
      validators.push(Validators.required);
    }
    if (field.min !== undefined) {
      validators.push(Validators.min(field.min));
    }
    if (field.max !== undefined) {
      validators.push(Validators.max(field.max));
    }
    if (field.maxLength !== undefined) {
      validators.push(Validators.maxLength(field.maxLength));
    }
    if (field.pattern) {
      validators.push(Validators.pattern(field.pattern));
    }
    return validators;
  }
}
