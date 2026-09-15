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
import { Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { PidGains, PidTuneRequest, PidTuneStatus, pidTunePending,
  zieglerNichols } from '@shared/models/inferrix-logic.models';
import { ControllerPanelComponent } from '@home/pages/inferrix/controller/controller-panel.component';

/**
 * Relay auto-tune for one PID slot.
 *
 * The controller drives the loop as a relay, measures the limit cycle it induces, and reports `Ku`
 * and `Tu` with a suggested gain set. It does not rewrite the running loop, and neither does this
 * panel: the gains live in the program's tags, so applying a result means editing the program and
 * pushing it. That is the firmware's decision and it is the right one — a tuner that silently
 * rewrote a live loop would be a much worse thing to own.
 *
 * **This oscillates real plant.** The relay is clamped into the loop's own output range and stops
 * on timeout or abort, but for a few cycles the process swings on purpose, which is why starting
 * one asks first.
 *
 * The slot is typed rather than chosen from the program in the editor next door. The editor holds a
 * draft; the tune acts on whatever is actually running, and offering the draft's slots would be a
 * confident answer to a question the platform cannot see.
 */
@Component({
  selector: 'tb-controller-tune',
  templateUrl: './controller-tune.component.html',
  styleUrls: ['./controller-tune.component.scss'],
  standalone: false
})
export class ControllerTuneComponent extends ControllerPanelComponent {

  request: PidTuneRequest = {
    slot: 0,
    outHigh: 100,
    outLow: 0,
    hysteresis: 1,
    timeoutMs: 600000
  };

  status: PidTuneStatus = null;
  error: string = null;
  busy = false;

  private poll: Subscription;
  private watching = false;

  constructor(private controllerService: InferrixControllerService,
              private dialogService: DialogService,
              private translate: TranslateService) {
    super();
  }

  protected load(): void {
    this.read();
  }

  /**
   * True while the device is still working, so the buttons and the spinner agree with the poller.
   *
   * A start is only *requested* — the scan task arms the relay at the next scan boundary — so the
   * slot reads `idle` for a moment afterwards. Keying this off the reported state alone would put
   * the Start button back during that window and let an operator start the same tune twice.
   */
  get running(): boolean {
    return this.watching || this.status?.state === 'running';
  }

  /**
   * Every field here is a number the device needs, and a blank one must not travel as something
   * else. An empty number input is `null`: the hysteresis would reach the device as a valid-looking
   * `0`, which the firmware documents as the setting that lets sensor noise chatter the relay and
   * destroy the measurement, and the slot would reach the proxy as the path `/logic/tune/null`,
   * refused by the allowlist with a message about routes that means nothing to whoever left a field
   * empty.
   */
  get valid(): boolean {
    return [this.request.slot, this.request.outHigh, this.request.outLow,
      this.request.hysteresis, this.request.timeoutMs].every(value => Number.isFinite(value));
  }

  get slotValid(): boolean {
    return Number.isFinite(this.request.slot);
  }

  get gains(): PidGains {
    return this.status?.state === 'done'
      ? {kp: this.status.kp, ki: this.status.ki, kd: this.status.kd} : null;
  }

  /** The same measurement under the other common rule; see `zieglerNichols`. */
  get classic(): PidGains {
    return this.status?.state === 'done' && this.status.ku && this.status.tu_s
      ? zieglerNichols(this.status.ku, this.status.tu_s) : null;
  }

  slotChanged(): void {
    this.poll?.unsubscribe();
    this.watching = false;
    this.status = null;
    if (this.slotValid) {
      this.read();
    }
  }

  read(): void {
    if (!this.slotValid) {
      return;
    }
    this.error = null;
    this.controllerService.getPidTune(this.deviceId, this.request.slot).subscribe({
      next: status => {
        this.status = status;
        // Only adopt a tune that was already under way; re-arming an active poller here would
        // push its deadline out every time Refresh is pressed.
        if (!this.watching && status?.state === 'running') {
          this.watch();
        }
      },
      error: error => this.error = this.messageOf(error)
    });
  }

  start(): void {
    if (!this.valid) {
      return;
    }
    this.dialogService.confirm(
      this.translate.instant('inferrix.tune-start-title'),
      this.translate.instant('inferrix.tune-start-text', {slot: this.request.slot}),
      this.translate.instant('action.cancel'),
      this.translate.instant('inferrix.tune-start')
    ).subscribe(confirmed => {
      if (!confirmed) {
        return;
      }
      this.busy = true;
      this.error = null;
      this.status = null;
      this.controllerService.startPidTune(this.deviceId, this.request).subscribe({
        next: () => {
          this.busy = false;
          this.watch();
        },
        error: error => {
          this.busy = false;
          this.error = this.messageOf(error);
        }
      });
    });
  }

  abort(): void {
    this.busy = true;
    this.controllerService.abortPidTune(this.deviceId, this.request.slot).subscribe({
      next: () => {
        this.busy = false;
        this.watch();
      },
      error: error => {
        this.busy = false;
        this.error = this.messageOf(error);
      }
    });
  }

  /**
   * Polls until the slot reaches a state that is not going to change on its own.
   *
   * The deadline matters. A start is only *requested* — the scan task arms it at the next scan
   * boundary — so a slot can read `idle` for a moment after starting and the poller has to wait
   * through that. Without a deadline it would also wait through the case where the arm never
   * happens at all, forever, on a two-client device.
   */
  private watch(): void {
    this.poll?.unsubscribe();
    this.watching = true;
    const deadline = Date.now() + this.request.timeoutMs + 15000;
    // Five seconds, not the two an upload gets. A tune runs for minutes, and every poll takes one
    // of the device's two client slots for the length of a TLS handshake.
    this.poll = timer(0, 5000).pipe(
      switchMap(() => this.controllerService.getPidTune(this.deviceId, this.request.slot)),
      takeWhile(status => pidTunePending(status?.state) && Date.now() < deadline, true)
    ).subscribe({
      next: status => this.status = status,
      error: error => {
        this.watching = false;
        this.error = this.messageOf(error);
      },
      complete: () => this.watching = false
    });
  }

  ngOnDestroy(): void {
    this.watching = false;
    this.poll?.unsubscribe();
    super.ngOnDestroy();
  }
}
