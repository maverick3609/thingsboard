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

import { Component, Input, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import {
  bitsToFloat,
  CONTROLLER_CONFIG_SECTIONS,
  ControllerConfigSection,
  ControllerSettingField,
  DATA_FORMATS,
  ICC_VERIFY_ERRORS,
  POINT_SOURCES
} from '@shared/models/inferrix-controller.models';
import { ControllerConfigRecordDialogComponent } from './controller-config-record-dialog.component';

/**
 * The controller's config plane: the buses, queries, points, scalings, publish policies and peer
 * table that compile into the binary image the device runs.
 *
 * Two things shape this screen. **Editing is on the draft, never on what is running** — the device
 * holds an in-RAM draft that is compiled, verified and hot-swapped in only when the operator
 * applies it, so nothing here changes plant behaviour until Apply succeeds. And **the draft is a
 * single shared object under one shared token**: the firmware has no per-editor lock, so a section
 * is re-read after every write rather than patched locally, and the operator is told when they are
 * not the owner.
 */
@Component({
  selector: 'tb-controller-config',
  templateUrl: './controller-config.component.html',
  styleUrls: ['./controller-config.component.scss'],
  standalone: false
})
export class ControllerConfigComponent implements OnInit {

  @Input() deviceId: string;
  @Input() readonly = false;

  readonly sections = CONTROLLER_CONFIG_SECTIONS;

  section: ControllerConfigSection = CONTROLLER_CONFIG_SECTIONS[0];
  records: any[] = [];
  columns: string[] = [];

  /** `local` = this REST surface owns the config; `platform` = a manifest does and CRUD is refused. */
  owner: string;
  showDraft = true;
  loading = false;
  error: string;
  applyResult: string;

  /** Identifies the newest read, so a slow one for a section the operator left cannot land. */
  private readToken = 0;

  constructor(private controllerService: InferrixControllerService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService) {}

  ngOnInit(): void {
    this.controllerService.getConfigOwner(this.deviceId).subscribe({
      next: value => this.owner = value?.owner,
      error: () => this.owner = null
    });
    this.selectSection(this.section);
  }

  get editable(): boolean {
    return !this.readonly && this.showDraft && this.owner === 'local';
  }

  selectSection(section: ControllerConfigSection): void {
    this.section = section;
    this.columns = [...section.columns, 'actions'];
    this.applyResult = null;
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.records = [];
    // A section read pages, so it can take a while; clicking through sections would otherwise let
    // an earlier read land its records under a later section's columns.
    const token = ++this.readToken;
    this.controllerService.readConfigSection(this.deviceId, this.section, this.showDraft).subscribe({
      next: records => {
        if (token !== this.readToken) {
          return;
        }
        this.records = records;
        this.loading = false;
      },
      error: error => {
        if (token !== this.readToken) {
          return;
        }
        this.error = this.messageOf(error);
        this.loading = false;
      }
    });
  }

  toggleDraft(showDraft: boolean): void {
    this.showDraft = showDraft;
    this.reload();
  }

  setOwner(owner: string): void {
    this.controllerService.setConfigOwner(this.deviceId, owner).subscribe({
      next: () => {
        this.owner = owner;
        this.reload();
      },
      error: error => this.error = this.messageOf(error)
    });
  }

  addRecord(): void {
    this.openRecord(null);
  }

  editRecord(record: any): void {
    this.openRecord(record);
  }

  deleteRecord(record: any): void {
    const id = Number(record[this.section.idField]);
    this.dialogService.confirm(
      this.translate.instant('inferrix.delete-record-title'),
      this.translate.instant('inferrix.delete-record-text', {id, section: this.translate.instant(this.section.titleKey)}),
      this.translate.instant('action.cancel'),
      this.translate.instant('action.delete')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.controllerService.deleteConfigRecord(this.deviceId, this.section, id).subscribe({
          next: () => this.reload(),
          error: error => this.error = this.messageOf(error)
        });
      }
    });
  }

  apply(): void {
    this.dialogService.confirm(
      this.translate.instant('inferrix.apply-title'),
      this.translate.instant('inferrix.apply-text'),
      this.translate.instant('action.cancel'),
      this.translate.instant('inferrix.apply')
    ).subscribe(confirmed => {
      if (!confirmed) {
        return;
      }
      this.loading = true;
      this.error = null;
      this.applyResult = null;
      this.controllerService.applyConfig(this.deviceId).subscribe({
        next: result => {
          this.loading = false;
          this.applyResult = this.translate.instant('inferrix.apply-ok',
            {version: result?.iccVersion, activation: result?.activation});
          this.reload();
        },
        error: error => {
          this.loading = false;
          this.error = this.applyError(error);
        }
      });
    });
  }

  discard(): void {
    this.dialogService.confirm(
      this.translate.instant('inferrix.discard-title'),
      this.translate.instant('inferrix.discard-text'),
      this.translate.instant('action.cancel'),
      this.translate.instant('inferrix.discard')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.controllerService.discardConfig(this.deviceId).subscribe({
          next: () => this.reload(),
          error: error => this.error = this.messageOf(error)
        });
      }
    });
  }

  /** Renders a raw record value the way its field spec says it means something. */
  display(record: any, key: string): string {
    const value = record[key];
    if (value === undefined || value === null) {
      return '—';
    }
    const field = this.section.fields.find(f => f.key === key);
    if (field?.float32Bits) {
      return String(bitsToFloat(Number(value)));
    }
    if (field?.type === 'flags') {
      return this.flagsLabel(field, Number(value));
    }
    if (field?.type === 'select') {
      return field.options?.find(option => option.value === value)?.label ?? String(value);
    }
    if (key === 'source') {
      return POINT_SOURCES.find(option => option.value === value)?.label ?? String(value);
    }
    if (key === 'data_format') {
      return DATA_FORMATS.find(option => option.value === value)?.label ?? String(value);
    }
    // 65535 is the point-side "no scaling" marker; showing the number reads like a real index.
    if (key === 'scaling_idx' && Number(value) === 65535) {
      return this.translate.instant('inferrix.scaling-none');
    }
    return String(value);
  }

  private flagsLabel(field: ControllerSettingField, value: number): string {
    const set = (field.bits ?? []).filter(bit => (value & bit.value) !== 0)
      .map(bit => this.translate.instant(bit.label));
    return set.length ? set.join(', ') : this.translate.instant('inferrix.flags-none');
  }

  private openRecord(record: any): void {
    this.dialog.open(ControllerConfigRecordDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {deviceId: this.deviceId, section: this.section, record}
    }).afterClosed().subscribe(saved => {
      if (saved) {
        this.reload();
      }
    });
  }

  /**
   * An apply rejection carries the verifier's own error name, which is the only thing that says
   * what is actually wrong with the draft; the HTTP status alone says nothing useful.
   */
  private applyError(error: any): string {
    const name = error?.error?.error;
    if (name === 'swap_in_progress') {
      return this.translate.instant('inferrix.apply-swap-in-progress');
    }
    if (name && ICC_VERIFY_ERRORS[name]) {
      return `${name} — ${ICC_VERIFY_ERRORS[name]}`;
    }
    return name ? `${name}` : this.messageOf(error);
  }

  private messageOf(error: any): string {
    return error?.error?.message || error?.message || 'Request failed';
  }
}
