// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { AppState } from '@core/core.state';
import { DialogComponent } from '@shared/components/dialog.component';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { bitsToFloat, COIL_FUNCTIONS, ControllerConfigSection, controllerRecordError,
  ControllerRefSection, ControllerSettingField, FLOAT_DATA_FORMATS, floatToBits,
  FORMAT_WIDTH_REGISTERS, LOCAL_POINT_SOURCES, LOCAL_SOURCE_CHANNEL_PREFIX, LOCAL_SOURCE_IO_KEY,
  MAX_COUNT_BITS, MAX_COUNT_REGISTERS, MAX_LOCAL_CHANNELS, MAX_PEER_ID, nextFreeId,
  NO_SCALING, PEER_POINT_SOURCE,
  READ_ONLY_FUNCTIONS, REF_SECTION_ID_FIELD, refOptionLabel,
  RTU_POINT_SOURCE } from '@shared/models/inferrix-controller.models';

export interface ControllerConfigRecordDialogData {
  deviceId: string;
  section: ControllerConfigSection;
  /** Absent when adding. */
  record?: any;
  /** Records of every section this one references, by section key. A section missing means its read failed. */
  refRecords?: {[section: string]: any[]};
  /** Keys already used in this section: what the id picker must not offer, and what autoId skips. */
  takenKeys?: number[];
  /** The board's own channel counts, for a local point's channel picker. Absent if unread. */
  ioCounts?: {[kind: string]: number};
}

/**
 * One config record, built from its section's field spec.
 *
 * Writes are always **full records** — the device has no partial edit — so an add and an edit are
 * the same request, and the only difference here is that the key field is frozen once a record
 * exists: changing it would leave the original behind and append a second record.
 *
 * **Every value the firmware enumerates is chosen, never typed.** A function code, a baud rate, a
 * framing byte and every id that names another section are all small integers whose meaning is
 * nowhere on screen, and the device does not check most of them until the whole draft is applied —
 * at which point it answers with one enum name and no field. So a wrong number costs a round trip
 * and a guess. The rules the verifier applies across fields are enforced here too, for the same
 * reason: `ICC_BAD_POINT` cannot say "your format does not match your query's function code", and
 * this dialog can.
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
  /** The fields with an input of their own, in order: everything but the ones sent as a constant. */
  readonly visibleFields: ControllerSettingField[];

  recordForm: UntypedFormGroup;
  errorMessage: string = null;

  private refOptionsByKey: {[key: string]: {value: any; label: string}[]} = {};

  constructor(protected store: Store<AppState>,
              protected router: Router,
              @Inject(MAT_DIALOG_DATA) public data: ControllerConfigRecordDialogData,
              public dialogRef: MatDialogRef<ControllerConfigRecordDialogComponent, boolean>,
              private fb: UntypedFormBuilder,
              private translate: TranslateService,
              private controllerService: InferrixControllerService) {
    super(store, router, dialogRef);
    this.section = data.section;
    this.isAdd = !data.record;
    this.visibleFields = this.section.fields.filter(field => !field.hidden);
    const record = data.record ?? {};
    this.recordForm = this.fb.group(Object.fromEntries(this.section.fields.map(field =>
      [field.key, [this.initialValue(field, record), this.validatorsFor(field)]])));
    // The id is the platform's to allocate, not the operator's to invent, so it is disabled on an
    // add as well as on an edit -- and for the same underlying reason either way: the only thing
    // typing one can do is collide with a record that already exists, which a write silently
    // overwrites because every write is an upsert.
    if (!this.isAdd || this.idField?.autoId) {
      this.recordForm.get(this.section.idField)?.disable();
    }
    if (this.isPointSection) {
      const source = this.recordForm.get('source');
      this.applySourceRules(source.value);
      source.valueChanges.subscribe(value => {
        this.applySourceRules(value);
        this.rebuildRefOptions();
      });
      // The query decides which formats are legal and whether the point may be writable, so both
      // have to be re-checked when it changes.
      this.recordForm.get('source_ref').valueChanges.subscribe(() => this.applyQueryRules());
      this.applyQueryRules();
    }
    if (this.isQuerySection) {
      const fc = this.recordForm.get('function');
      this.applyCountLimit(fc.value);
      fc.valueChanges.subscribe(value => this.applyCountLimit(value));
    }
    this.rebuildRefOptions();
  }

  get isPointSection(): boolean {
    return this.section.key === 'points';
  }

  get isQuerySection(): boolean {
    return this.section.key === 'queries';
  }

  /** The spec of this section's key field. */
  get idField(): ControllerSettingField {
    return this.section.fields.find(field => field.key === this.section.idField);
  }

  /**
   * The id this record will be saved under, for the read-only line that replaces the id input.
   *
   * Allocated once, when the dialog opens, rather than recomputed: the operator is looking at it,
   * and a number that moved while they filled the form in would be worse than no number at all.
   */
  get allocatedId(): number {
    return this.recordForm.get(this.section.idField)?.value;
  }

  /** True when the section is full, so there is no id left to allocate and Save cannot succeed. */
  get sectionFull(): boolean {
    return this.isAdd && this.idField?.autoId && this.allocatedId === null;
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

  /** The message under an empty picker: the referenced section has nothing to offer yet. */
  emptyRefHint(field: ControllerSettingField): string {
    return `inferrix.no-${field.optionsFrom}-to-pick`;
  }

  /**
   * Builds the pickers once per source change, not per change-detection pass: a points section holds
   * up to 1024 records, and rebuilding that list in a template expression would rebuild it on every
   * keystroke in the dialog.
   */
  private rebuildRefOptions(): void {
    this.refOptionsByKey = {};
    this.section.fields.filter(field => field.optionsFrom).forEach(field => {
      const records = this.data.refRecords?.[field.optionsFrom];
      if (!records) {
        return;
      }
      if (field.optionsFrom === 'queries'
          && this.recordForm.get('source')?.value !== RTU_POINT_SOURCE) {
        return;
      }
      const idKey = REF_SECTION_ID_FIELD[field.optionsFrom];
      const own = Number(this.data.record?.[field.key]);
      // Only a policy's key is exclusive -- one per point. Every other reference is shared: many
      // points read one query, many queries sit on one bus, many points use one scaling.
      const taken = field.keyField ? this.data.takenKeys ?? [] : [];
      const options = records
        .filter(record => Number(record[idKey]) === own || !taken.includes(Number(record[idKey])))
        .map(record => ({value: Number(record[idKey]),
          label: refOptionLabel(field.optionsFrom as ControllerRefSection, record)}));
      // Not a scaling id at all: the point-side marker for "publish the raw reading". It belongs
      // in the list because it is what most points want, and 65535 typed into a number box is the
      // least obvious way to say so.
      if (field.key === 'scaling_idx') {
        options.unshift({value: NO_SCALING, label: this.translate.instant('inferrix.scaling-none')});
      }
      this.refOptionsByKey[field.key] = options;
    });
    this.addChannelOptions();
  }

  /**
   * The channel picker for a local point.
   *
   * `source_ref` is a different quantity for every source: a query id on a Modbus point, a channel
   * index on a local one, a remote point id on a peer one. For the four local sources the firmware
   * bounds it by the board's own channel count -- `source_ref >= io->di` is `ICC_BAD_POINT` -- so
   * the legal values are known exactly and there is nothing for the operator to type.
   *
   * Only offered when the board itself reported its counts. A board that did not answer keeps the
   * number box: guessing its channel count from the platform's default profile would offer channels
   * that may not exist, and hide ones that do. A count past {@link MAX_LOCAL_CHANNELS} is treated
   * the same way -- it is the device's own number, and building a list from it unchecked is how a
   * board that answers `"di": 1e9` locks the browser.
   */
  private addChannelOptions(): void {
    if (!this.isPointSection) {
      return;
    }
    const source = Number(this.recordForm.get('source')?.value);
    const kind = LOCAL_SOURCE_IO_KEY[source];
    const count = kind ? Number(this.data.ioCounts?.[kind]) : 0;
    if (!kind || !Number.isInteger(count) || count <= 0 || count > MAX_LOCAL_CHANNELS) {
      return;
    }
    const prefix = LOCAL_SOURCE_CHANNEL_PREFIX[source];
    this.refOptionsByKey.source_ref = Array.from({length: count}, (unused, channel) =>
      // The board is silkscreened from 1 and the wire format counts from 0, so both are shown:
      // the operator is looking at a terminal marked DI1 while the record has to say 0.
      ({value: channel, label: `${prefix}${channel + 1} (channel ${channel})`}));
  }

  /**
   * The options a select offers, narrowed by what the rest of the record already says.
   *
   * Both narrowings are verifier rules, not preferences: the firmware rejects the whole draft for
   * either one, naming only `ICC_BAD_POINT`.
   */
  optionsFor(field: ControllerSettingField): {value: any; label: string}[] {
    if (field.key !== 'data_format') {
      return field.options;
    }
    // A local point is offered no float format: the controller refuses one at apply (0.1.16).
    if (this.isLocalSource()) {
      return field.options.filter(option => !FLOAT_DATA_FORMATS.includes(option.value));
    }
    // On a Modbus point the format has to match the kind of object the query reads: a bit from a
    // coil query, a register format from a register query. Never both.
    const coil = this.selectedQueryIsCoil();
    if (coil === null) {
      return field.options;
    }
    const bit = DATA_FORMAT_BIT;
    return field.options.filter(option => (option.value === bit) === coil);
  }

  /**
   * The warning for opening a local output to remote writes, or null. A program can drive the same
   * output, and the two then fight over it.
   */
  get writableOutputWarning(): string {
    if (!this.isPointSection || !this.isWritable) {
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

  /**
   * Why the chosen query cannot carry a writable point, or null.
   *
   * FC2 and FC4 read discrete inputs and input registers, which have no write on the wire at all.
   * The verifier refuses the draft for this and says `ICC_BAD_POINT`, which names neither the flag
   * nor the query.
   */
  get writableQueryWarning(): string {
    if (!this.isPointSection || !this.isWritable
        || this.recordForm.get('source').value !== RTU_POINT_SOURCE) {
      return null;
    }
    const query = this.selectedQuery();
    return query && READ_ONLY_FUNCTIONS.includes(Number(query.function))
      ? 'inferrix.writable-read-only-query' : null;
  }

  /** How many registers past the query's window this point's format would read, or 0. */
  get offsetOverrun(): number {
    if (!this.isPointSection || this.recordForm.get('source').value !== RTU_POINT_SOURCE) {
      return 0;
    }
    const query = this.selectedQuery();
    const offset = Number(this.recordForm.get('offset').value);
    if (!query || !Number.isFinite(offset)) {
      return 0;
    }
    const count = Number(query.count);
    const width = COIL_FUNCTIONS.includes(Number(query.function))
      ? 1 : FORMAT_WIDTH_REGISTERS[Number(this.recordForm.get('data_format').value)] ?? 1;
    return Math.max(0, offset + width - count);
  }

  private get isWritable(): boolean {
    return ((this.recordForm.get('flags')?.value | 0) & 1) !== 0;
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
    // getRawValue, not value: the key field is disabled -- allocated when adding, frozen when
    // editing -- and still has to be sent, as does a scaling locked off by the source rules.
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

  /** The query record this point reads from, or null when none is chosen or the read failed. */
  private selectedQuery(): any {
    const id = Number(this.recordForm.get('source_ref')?.value);
    return (this.data.refRecords?.queries ?? []).find(query => Number(query.query_id) === id) ?? null;
  }

  /** Whether the chosen query reads bits, or null when there is no query to ask. */
  private selectedQueryIsCoil(): boolean {
    if (this.recordForm.get('source')?.value !== RTU_POINT_SOURCE) {
      return null;
    }
    const query = this.selectedQuery();
    return query ? COIL_FUNCTIONS.includes(Number(query.function)) : null;
  }

  /**
   * Keeps the format legal for the query that is now selected.
   *
   * Clearing rather than correcting: which register format a device reports in is the operator's
   * to know, and picking one for them would be a guess that reads plausibly and is wrong.
   */
  private applyQueryRules(): void {
    const format = this.recordForm.get('data_format');
    const coil = this.selectedQueryIsCoil();
    if (coil === null || format.value === null || format.value === undefined) {
      return;
    }
    if ((Number(format.value) === DATA_FORMAT_BIT) !== coil) {
      format.setValue(coil ? DATA_FORMAT_BIT : null);
    }
  }

  /**
   * Modbus's own per-request ceiling, which differs by function code: 2000 bits or 125 registers.
   * The verifier enforces exactly this, and the form has to move with the function code or it
   * would allow 2000 registers the moment the operator switched from coils.
   */
  private applyCountLimit(fc: number): void {
    const count = this.recordForm.get('count');
    const max = COIL_FUNCTIONS.includes(Number(fc)) ? MAX_COUNT_BITS : MAX_COUNT_REGISTERS;
    count.setValidators([Validators.required, Validators.min(1), Validators.max(max)]);
    count.updateValueAndValidity();
  }

  /**
   * What the controller refuses for a local source at apply (ICC_BAD_LOCAL_POINT): a scaling on a
   * DI or DO, and a float format on any of them. Refused here instead, so the operator finds out
   * while editing the record rather than when the whole draft is rejected.
   */
  private applySourceRules(source: number): void {
    // On a peer point `offset` is not an offset at all -- it is the peer id, and `check_peers`
    // refuses anything past 3. On every other source it addresses the query window and keeps its
    // own much larger range.
    const offset = this.recordForm.get('offset');
    offset.setValidators([Validators.required, Validators.min(0),
      Validators.max(source === PEER_POINT_SOURCE ? MAX_PEER_ID : 65535)]);
    offset.updateValueAndValidity({emitEvent: false});
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
    if (this.isAdd && field.autoId) {
      return nextFreeId(this.section, this.data.takenKeys);
    }
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

/** `DATA_FORMATS` value for a single bit — the only format a coil query can carry. */
const DATA_FORMAT_BIT = 6;
