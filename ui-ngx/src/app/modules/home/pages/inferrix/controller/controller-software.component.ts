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

import { Component } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { concat, of, Subscription, timer } from 'rxjs';
import { catchError, filter, switchMap, takeUntil, takeWhile, tap, toArray } from 'rxjs/operators';
import { DialogService } from '@core/services/dialog.service';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { CONTROLLER_UPLOAD_LIMITS, ControllerUploadKind, ControllerUploadStatus, firmwareVersionOf,
  sameFirmwareRelease } from '@shared/models/inferrix-controller.models';
import { ControllerPanelComponent } from '@home/pages/inferrix/controller/controller-panel.component';

/**
 * Writing a firmware image or a logic program to the controller.
 *
 * The upload runs on the platform, not here: an image is several hundred chunks against the
 * device's 2048-byte request cap and the run takes minutes, so this hands over the file once and
 * then polls a job. Closing the page does not stop it — the job outlives the screen, which is why
 * the state is re-read on load rather than only tracked in memory.
 *
 * Both kinds activate on **reboot**, which is why the restart lives here too. Firmware additionally
 * has to self-confirm its test boot, so a version that will not run reverts on its own — and the
 * only way to tell is to compare what the controller runs afterwards with what was uploaded.
 */
@Component({
  selector: 'tb-controller-software',
  templateUrl: './controller-software.component.html',
  standalone: false
})
export class ControllerSoftwareComponent extends ControllerPanelComponent {

  readonly kinds: {kind: ControllerUploadKind; titleKey: string; noteKey: string;
    statusPath: string; accept: string}[] = [
    {kind: 'FIRMWARE', titleKey: 'inferrix.firmware', noteKey: 'inferrix.firmware-upload-note',
      statusPath: '/api/v1/firmware/status', accept: '.bin,.img,application/octet-stream'},
    {kind: 'LOGIC', titleKey: 'inferrix.logic-program', noteKey: 'inferrix.logic-upload-note',
      statusPath: '/api/v1/logic/status', accept: '.ilb,application/octet-stream'}
  ];

  deviceStatus: {[kind: string]: any} = {};
  /** GET /api/v1/info — the running firmware version. */
  running: any;
  selected: {[kind: string]: File} = {};
  job: {[kind: string]: ControllerUploadStatus} = {};
  errors: {[kind: string]: string} = {};

  loading = false;

  restarting = false;
  restartError: string;
  restartOutcome: string;

  readonly versionOf = firmwareVersionOf;

  private polls: {[kind: string]: Subscription} = {};

  constructor(private controllerService: InferrixControllerService,
              private dialogService: DialogService,
              private translate: TranslateService) {
    super();
  }

  protected load(): void {
    this.reload();
    // An upload started before this page was loaded is still running on the platform; pick it back
    // up rather than showing a device that refuses a second upload and no reason why.
    this.controllerService.getActiveUpload(this.deviceId, {ignoreErrors: true}).subscribe({
      next: status => {
        if (status) {
          this.job[status.kind] = status;
          this.poll(status.kind, status.jobId);
        }
      },
      error: () => {}
    });
  }

  override ngOnDestroy(): void {
    Object.values(this.polls).forEach(poll => poll?.unsubscribe());
    super.ngOnDestroy();
  }

  reload(): void {
    this.loading = true;
    // One at a time; the device serves two clients and one of those slots may already belong to a
    // running upload.
    concat(this.get('/api/v1/info'), this.get(this.kinds[0].statusPath), this.get(this.kinds[1].statusPath))
      .pipe(toArray(), takeUntil(this.destroy$))
      .subscribe(([info, firmware, logic]) => {
        this.running = info;
        this.deviceStatus.FIRMWARE = firmware;
        this.deviceStatus.LOGIC = logic;
        this.loading = false;
      });
  }

  get uploading(): boolean {
    return Object.values(this.job).some(status => status?.state === 'RUNNING');
  }

  /**
   * Restarts the controller so a staged image or program activates, waits for it to answer again,
   * and says whether an image uploaded from this page is the one now running.
   */
  restart(): void {
    this.dialogService.confirm(
      this.translate.instant('inferrix.restart-controller-title'),
      this.translate.instant('inferrix.restart-controller-text'),
      this.translate.instant('action.cancel'),
      this.translate.instant('inferrix.restart-controller')
    ).pipe(
      filter(confirmed => confirmed),
      tap(() => {
        this.restarting = true;
        this.restartError = null;
        this.restartOutcome = null;
      }),
      switchMap(() => this.controllerService.rebootController(this.deviceId)),
      switchMap(() => this.controllerService.awaitController(this.deviceId)),
      takeUntil(this.destroy$)
    ).subscribe({
      next: info => {
        this.restarting = false;
        this.restartOutcome = this.outcomeOf(info);
        this.reload();
      },
      error: error => {
        this.restarting = false;
        this.restartError = error?.name === 'TimeoutError'
          ? this.translate.instant('inferrix.restart-timeout') : this.messageOf(error);
      }
    });
  }

  pick(kind: ControllerUploadKind, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.length ? input.files[0] : null;
    this.errors[kind] = null;
    // The platform enforces this too, but telling the operator now beats uploading a megabyte to
    // be told it was the wrong file.
    if (file && file.size > CONTROLLER_UPLOAD_LIMITS[kind]) {
      this.selected[kind] = null;
      this.errors[kind] = this.translate.instant('inferrix.file-too-large',
        {size: file.size, limit: CONTROLLER_UPLOAD_LIMITS[kind]});
      return;
    }
    this.selected[kind] = file;
  }

  upload(kind: ControllerUploadKind): void {
    const file = this.selected[kind];
    if (!file) {
      return;
    }
    this.dialogService.confirm(
      this.translate.instant(kind === 'FIRMWARE' ? 'inferrix.upload-firmware-title'
        : 'inferrix.upload-logic-title'),
      this.translate.instant(kind === 'FIRMWARE' ? 'inferrix.upload-firmware-text'
        : 'inferrix.upload-logic-text', {name: file.name, size: file.size}),
      this.translate.instant('action.cancel'),
      this.translate.instant('inferrix.upload')
    ).subscribe(confirmed => {
      if (!confirmed) {
        return;
      }
      this.errors[kind] = null;
      this.controllerService.uploadArtifact(this.deviceId, kind, file, {ignoreErrors: true})
        .subscribe({
          next: status => {
            this.job[kind] = status;
            this.poll(kind, status.jobId);
          },
          error: error => this.errors[kind] = this.messageOf(error)
        });
    });
  }

  /**
   * The two status endpoints disagree on shape: firmware reports a state string, the logic runtime
   * reports the numeric enum from §3.8. Rendering the number raw would show an operator "1".
   */
  stateLabel(kind: ControllerUploadKind): string {
    const state = this.deviceStatus[kind]?.state;
    if (state === undefined || state === null) {
      return '—';
    }
    if (kind === 'LOGIC' && typeof state === 'number') {
      return this.translate.instant(LOGIC_STATES[state] ?? 'inferrix.unknown');
    }
    return String(state);
  }

  progress(kind: ControllerUploadKind): number {
    const status = this.job[kind];
    return status?.total ? Math.round((status.sent / status.total) * 100) : 0;
  }

  private poll(kind: ControllerUploadKind, jobId: string): void {
    this.polls[kind]?.unsubscribe();
    this.polls[kind] = timer(1000, 2000).pipe(
      switchMap(() => this.controllerService.getUploadStatus(jobId, {ignoreErrors: true})),
      // Inclusive, so the terminal state is the last thing rendered rather than being dropped.
      takeWhile(status => status.state === 'RUNNING', true)
    ).subscribe({
      next: status => {
        this.job[kind] = status;
        if (status.state !== 'RUNNING') {
          this.reload();
        }
      },
      error: error => this.errors[kind] = this.messageOf(error)
    });
  }

  private outcomeOf(info: any): string {
    const version = firmwareVersionOf(info?.fw) ?? '—';
    const firmware = this.job.FIRMWARE;
    if (firmware?.state !== 'DONE' || !firmware.imageVersion) {
      return this.translate.instant('inferrix.restart-done', {version});
    }
    return this.translate.instant(sameFirmwareRelease(version, firmware.imageVersion)
      ? 'inferrix.restart-image-active' : 'inferrix.restart-image-not-active',
      {version, image: firmware.imageVersion});
  }

  private get(path: string) {
    return this.controllerService.proxy<any>(this.deviceId, 'GET', path, null, {ignoreErrors: true})
      .pipe(catchError(() => of(null)));
  }

}

const LOGIC_STATES: {[state: number]: string} = {
  0: 'inferrix.logic-none',
  1: 'inferrix.logic-running',
  2: 'inferrix.logic-faulted',
  3: 'inferrix.logic-load-failed'
};
