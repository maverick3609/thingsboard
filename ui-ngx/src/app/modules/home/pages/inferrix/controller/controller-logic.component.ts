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
import { Observable, Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerUploadStatus } from '@shared/models/inferrix-controller.models';
import { LOGIC_BINDINGS, LOGIC_SYSTEM_REGISTERS, LogicBinding, LogicCompileResult, LogicProgram,
  LogicTag } from '@shared/models/inferrix-logic.models';
import { ControllerPanelComponent } from '@home/pages/inferrix/controller/controller-panel.component';

/**
 * Writing a logic program for the controller.
 *
 * The program is built from statements rather than instructions. The controller runs a stack
 * machine, so even `fan = outside air < 18` is four instructions in an order that only makes sense
 * if you are thinking about a stack; the platform compiles the statements instead.
 *
 * **Verify is not optional politeness.** A controller that refuses a program says so on a serial
 * console and nowhere else: over REST a rejected program reports exactly what no program at all
 * reports, so an operator who pushes something broken sees a successful upload and then nothing
 * happening, forever. Everything is therefore checked on the platform first, against this device's
 * own profile and points, and Push refuses to write what would not run.
 */
@Component({
  selector: 'tb-controller-logic',
  templateUrl: './controller-logic.component.html',
  styleUrls: ['./controller-logic.component.scss'],
  standalone: false
})
export class ControllerLogicComponent extends ControllerPanelComponent {

  readonly bindings = LOGIC_BINDINGS;
  readonly registers = LOGIC_SYSTEM_REGISTERS;

  program: LogicProgram = {
    programId: 1,
    programVersion: 1,
    profile: 1,
    scanPeriodMs: 1000,
    tags: [],
    statements: []
  };

  /** The controller's configured points, so an ICC binding is a choice rather than a typed id. */
  points: {id: number; type: string; name?: string}[] = [];

  result: LogicCompileResult = null;
  error: string = null;
  job: ControllerUploadStatus = null;
  busy = false;
  loadingPoints = false;

  private poll: Subscription;

  constructor(private controllerService: InferrixControllerService) {
    super();
  }

  protected load(): void {
    this.loadingPoints = true;
    // One read, and only when the tab is opened: the firmware serves two clients at a time and
    // every request costs a TLS handshake.
    this.controllerService.readPoints(this.deviceId).subscribe({
      next: result => {
        this.points = (result.records || []).map(point => ({
          id: point.id, type: point.type, name: point.name
        }));
        this.loadingPoints = false;
      },
      // A controller with no configuration yet is a normal state, not an error worth a banner.
      error: () => this.loadingPoints = false
    });
  }

  addTag(): void {
    this.program.tags.push({
      name: this.uniqueName(), dataType: 'BOOL', cls: 'MEMORY', binding: 'NONE'
    });
  }

  removeTag(index: number): void {
    this.program.tags.splice(index, 1);
  }

  /** A tag's binding decides whether it needs an address, and what kind. */
  bindingSpec(binding: LogicBinding) {
    return this.bindings.find(entry => entry.value === binding);
  }

  onBindingChange(tag: LogicTag): void {
    const spec = this.bindingSpec(tag.binding);
    if (!spec?.needsAddress) {
      delete tag.address;
    } else if (tag.address === undefined) {
      tag.address = spec.fromPoints ? (this.points[0]?.id ?? 0) : 0;
    }
  }

  verify(): void {
    this.run(false);
  }

  push(): void {
    this.run(true);
  }

  /**
   * Compiles, and writes only when asked to and only when it would run.
   *
   * <p>Bumping the version on every push is what makes a push take effect: the controller boots the
   * highest program version it holds, so re-pushing the same number would leave the previous
   * program winning on a tie.
   */
  private run(write: boolean): void {
    this.busy = true;
    this.error = null;
    this.result = null;
    this.job = null;
    if (write) {
      this.program.programVersion = (this.program.programVersion || 0) + 1;
    }
    // One of two different response shapes, so the subscriber below reads it by branch rather than
    // by type; the alternative is duplicating the whole error path once per call.
    const request: Observable<any> = write
      ? this.controllerService.buildLogic(this.deviceId, this.program, {ignoreErrors: true})
      : this.controllerService.compileLogic(this.deviceId, this.program, {ignoreErrors: true});

    request.subscribe({
      next: (response: any) => {
        this.busy = false;
        if (write) {
          this.job = response;
          this.watch(response.jobId);
        } else {
          this.result = response;
          if (!response.ok) {
            this.error = response.message;
          }
        }
      },
      error: error => {
        this.busy = false;
        if (write) {
          // A refused build already bumped the version; putting it back keeps the number honest
          // about what the controller actually holds.
          this.program.programVersion -= 1;
        }
        this.error = this.messageOf(error);
      }
    });
  }

  private watch(jobId: string): void {
    this.poll?.unsubscribe();
    this.poll = timer(0, 2000).pipe(
      switchMap(() => this.controllerService.getUploadStatus(jobId, {ignoreErrors: true})),
      takeWhile(status => status?.state === 'RUNNING', true)
    ).subscribe({
      next: status => this.job = status,
      error: error => this.error = this.messageOf(error)
    });
  }

  private uniqueName(): string {
    let index = this.program.tags.length + 1;
    while (this.program.tags.some(tag => tag.name === `tag${index}`)) {
      index++;
    }
    return `tag${index}`;
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    super.ngOnDestroy();
  }
}
