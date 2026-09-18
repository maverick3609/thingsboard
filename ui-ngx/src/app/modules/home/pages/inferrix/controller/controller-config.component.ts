// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component, OnDestroy } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { from, Subscription, timer } from 'rxjs';
import { concatMap, switchMap, takeUntil, takeWhile, tap } from 'rxjs/operators';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import {
  bitsToFloat,
  CONTROLLER_CONFIG_SECTIONS,
  CONTROLLER_TEMPLATE_WRITE_ORDER,
  ControllerConfigSection,
  ControllerTemplate,
  ControllerProvisionStatus,
  ControllerSettingField,
  DATA_FORMATS,
  ICC_VERIFY_ERRORS,
  POINT_SOURCES
} from '@shared/models/inferrix-controller.models';
import { ControllerConfigRecordDialogComponent } from './controller-config-record-dialog.component';
import { ControllerTemplateDialogComponent, ControllerTemplateDialogData }
  from './controller-template-dialog.component';
import { ControllerPanelComponent } from '@home/pages/inferrix/controller/controller-panel.component';

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
  styleUrls: ['./controller-config.component.scss', './controller-table.scss'],
  standalone: false
})
export class ControllerConfigComponent extends ControllerPanelComponent implements OnDestroy {

  readonly sections = CONTROLLER_CONFIG_SECTIONS;

  section: ControllerConfigSection = CONTROLLER_CONFIG_SECTIONS[0];
  records: any[] = [];
  /** The slice on screen. A points section holds up to 1024 records; the device sends them all. */
  pagedRecords: any[] = [];
  readonly pageSizeOptions = [10, 20, 50, 100];
  pageSize = 10;
  pageIndex = 0;
  columns: string[] = [];

  /** `local` = this REST surface owns the config; `platform` = a manifest does and CRUD is refused. */
  owner: string;
  showDraft = true;
  loading = false;
  error: string;
  applyResult: string;
  /** Progress of a template read or write, both of which are one device call per step. */
  templateProgress: string;
  /** The local I/O provisioning job this tab started or found running, until the operator leaves. */
  provision: ControllerProvisionStatus;

  /** Identifies the newest read, so a slow one for a section the operator left cannot land. */
  private readToken = 0;
  private provisionPoll: Subscription;

  constructor(private controllerService: InferrixControllerService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              private cd: ChangeDetectorRef) {
    super();
  }

  protected load(): void {
    this.controllerService.getConfigOwner(this.deviceId).subscribe({
      next: value => this.owner = value?.owner,
      error: () => this.owner = null
    });
    this.selectSection(this.section);
    if (!this.readonly) {
      // Adoption provisions a controller that has never been configured, so the operator may well
      // arrive here while that is still writing to the draft.
      this.controllerService.getActiveProvision(this.deviceId, {ignoreErrors: true}).subscribe({
        next: status => {
          if (status) {
            this.watchProvision(status);
          }
        },
        error: () => this.provision = null
      });
    }
  }

  get editable(): boolean {
    return !this.readonly && this.showDraft && this.owner === 'local';
  }

  /** Applying or discarding while a job still writes would take half its records live, or lose them. */
  get provisioning(): boolean {
    return this.provision?.state === 'RUNNING';
  }

  provisionLocalIo(): void {
    this.error = null;
    this.applyResult = null;
    this.controllerService.provisionLocalIo(this.deviceId, {ignoreErrors: true}).subscribe({
      next: status => this.watchProvision(status),
      error: error => this.error = this.messageOf(error)
    });
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
    this.pagedRecords = [];
    // A section read pages, so it can take a while; clicking through sections would otherwise let
    // an earlier read land its records under a later section's columns.
    const token = ++this.readToken;
    this.controllerService.readConfigSection(this.deviceId, this.section, this.showDraft).subscribe({
      next: records => {
        if (token !== this.readToken) {
          return;
        }
        this.records = records;
        this.pageIndex = 0;
        this.slicePage();
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

  pageChanged(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.slicePage();
  }

  private slicePage(): void {
    const start = this.pageIndex * this.pageSize;
    this.pagedRecords = this.records.slice(start, start + this.pageSize);
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

  /**
   * Saves what is on screen — every section, not just the one selected — under a name.
   *
   * The read is the slow half: six sections, one device call each (more when a section pages), and
   * the firmware answers one at a time. It runs against whichever side the operator is looking at,
   * so a template can be taken from the running configuration as well as from the draft.
   */
  saveAsTemplate(): void {
    this.error = null;
    this.applyResult = null;
    const snapshot: {[sectionKey: string]: any[]} = {};
    let read = 0;
    this.templateProgress = this.translate.instant('inferrix.template-reading',
      {done: 0, total: this.sections.length});
    from(this.sections).pipe(
      concatMap(section => this.controllerService.readConfigSection(this.deviceId, section, this.showDraft)
        .pipe(tap(records => {
          snapshot[section.key] = records || [];
          this.templateProgress = this.translate.instant('inferrix.template-reading',
            {done: ++read, total: this.sections.length});
          this.cd.markForCheck();
        }))),
      takeUntil(this.destroy$)
    ).subscribe({
      complete: () => {
        this.templateProgress = null;
        this.openTemplateDialog('save', snapshot);
      },
      error: error => {
        this.templateProgress = null;
        this.error = this.messageOf(error);
      }
    });
  }

  applyTemplate(): void {
    this.error = null;
    this.applyResult = null;
    this.openTemplateDialog('apply');
  }

  private openTemplateDialog(mode: 'save' | 'apply', config?: {[sectionKey: string]: any[]}): void {
    this.dialog.open<ControllerTemplateDialogComponent, ControllerTemplateDialogData, ControllerTemplate>(
      ControllerTemplateDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          mode,
          kind: 'config',
          config,
          sourceName: this.deviceName,
          suggestedName: `${this.deviceName || 'Controller'} · ${new Date().toISOString().slice(0, 10)}`
        }
      }).afterClosed().subscribe(template => {
      if (!template) {
        return;
      }
      if (mode === 'save') {
        this.applyResult = this.translate.instant('inferrix.template-saved', {name: template.name});
      } else {
        this.writeTemplate(template);
      }
    });
  }

  /**
   * Writes a saved configuration into this controller's draft, one record at a time.
   *
   * Records the template does not name are left alone: this is an upsert by id, not a replacement of
   * the draft. Discard first for a clean base. Nothing goes live either way — Apply still has to
   * compile and verify the whole draft.
   *
   * ponytail: driven from the page, so it stops if the operator leaves the tab, and a full 1024-point
   * template takes about twenty minutes at the firmware's ~1.4s per write. Move it behind a job like
   * local I/O provisioning (InferrixProvisionService) if templates that size become normal.
   */
  private writeTemplate(template: ControllerTemplate): void {
    const writes: {section: ControllerConfigSection; record: any}[] = [];
    CONTROLLER_TEMPLATE_WRITE_ORDER.forEach(key => {
      const section = this.sections.find(candidate => candidate.key === key);
      if (section) {
        (template.config?.[key] || []).forEach(record => writes.push({section, record}));
      }
    });
    if (!writes.length) {
      this.error = this.translate.instant('inferrix.template-empty');
      return;
    }
    let written = 0;
    this.templateProgress = this.translate.instant('inferrix.template-writing',
      {done: 0, total: writes.length});
    from(writes).pipe(
      concatMap(write => this.controllerService.upsertConfigRecord(this.deviceId, write.section, write.record)
        .pipe(tap(() => {
          this.templateProgress = this.translate.instant('inferrix.template-writing',
            {done: ++written, total: writes.length});
          this.cd.markForCheck();
        }))),
      takeUntil(this.destroy$)
    ).subscribe({
      complete: () => {
        this.templateProgress = null;
        this.applyResult = this.translate.instant('inferrix.template-applied',
          {count: writes.length, name: template.name});
        this.reload();
      },
      error: error => {
        this.templateProgress = null;
        // Which record, not just which error: the write stops at the first refusal, and the rest of
        // the template is still on the operator's side of the wire.
        this.error = this.translate.instant('inferrix.template-write-failed',
          {done: written, total: writes.length, error: this.messageOf(error)});
        this.reload();
      }
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

  ngOnDestroy(): void {
    this.provisionPoll?.unsubscribe();
    super.ngOnDestroy();
  }

  /** A column is headed by its field's own label, the one the record dialog shows for it. */
  columnLabel(key: string): string {
    return this.section.fields.find(field => field.key === key)?.label ?? key;
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

  private watchProvision(status: ControllerProvisionStatus): void {
    this.provision = status;
    this.provisionPoll?.unsubscribe();
    if (status.state !== 'RUNNING') {
      this.reload();
      return;
    }
    this.provisionPoll = timer(PROVISION_POLL_MS, PROVISION_POLL_MS).pipe(
      switchMap(() => this.controllerService.getProvisionStatus(status.jobId,
        {ignoreErrors: true, ignoreLoading: true})),
      takeWhile(current => current?.state === 'RUNNING', true)
    ).subscribe({
      next: current => {
        this.provision = current;
        // The details page is OnPush and a poll that skips the loading bar does not mark it.
        this.cd.markForCheck();
        if (current?.state === 'DONE' && current.added) {
          // What changed is the points, and they are what the operator has to review.
          this.selectSection(this.sections.find(section => section.key === 'points'));
        } else if (current?.state !== 'RUNNING') {
          this.reload();
        }
      },
      error: error => {
        this.provision = null;
        this.error = this.messageOf(error);
        this.cd.markForCheck();
      }
    });
  }

  /**
   * Opens the record editor, first fetching whatever section this one points at.
   *
   * A policy names a point and an RTU point names a query, and both are stored as a bare id. Typed
   * by hand, a wrong one is only caught when the whole draft is applied — so the field is a picker,
   * and the picker needs the other section's records. The read is best-effort: if it fails the
   * dialog still opens and the field falls back to the plain number input it has always been.
   */
  private openRecord(record: any): void {
    const refKind = this.section.fields.find(field => field.optionsFrom)?.optionsFrom;
    const refSection = refKind && CONTROLLER_CONFIG_SECTIONS.find(candidate => candidate.key === refKind);
    if (!refSection) {
      this.showRecord(record, null);
      return;
    }
    this.loading = true;
    this.controllerService.readConfigSection(this.deviceId, refSection, this.showDraft).subscribe({
      next: refRecords => {
        this.loading = false;
        this.showRecord(record, refRecords);
      },
      error: () => {
        this.loading = false;
        this.showRecord(record, null);
      }
    });
  }

  private showRecord(record: any, refRecords: any[]): void {
    this.dialog.open(ControllerConfigRecordDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {deviceId: this.deviceId, section: this.section, record, refRecords,
             takenKeys: this.records.map(existing => Number(existing[this.section.idField]))}
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

}

/** A provisioning job is a few dozen requests to a microcontroller, so there is no hurry. */
const PROVISION_POLL_MS = 2000;
