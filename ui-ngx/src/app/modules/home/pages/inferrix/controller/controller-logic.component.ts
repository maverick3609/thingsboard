// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Observable, Subscription, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { CONTROLLER_CONFIG_SECTIONS, ControllerUploadStatus, isRealPoint,
  POINT_SOURCES } from '@shared/models/inferrix-controller.models';
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

  /**
   * The points in the controller's active configuration, so an ICC binding is a choice rather than a
   * typed id. `real` says whether the point holds a float, which decides the tag type it needs.
   */
  points: {id: number; source: number; type: string; name?: string; real: boolean; bit: boolean}[] = [];

  result: LogicCompileResult = null;
  error: string = null;
  job: ControllerUploadStatus = null;
  busy = false;
  loadingPoints = false;

  private poll: Subscription;

  constructor(private controllerService: InferrixControllerService,
              private translate: TranslateService) {
    super();
  }

  protected load(): void {
    this.loadingPoints = true;
    // One read, and only when the tab is opened: the firmware serves two clients at a time and
    // every request costs a TLS handshake. The active config's point records rather than the live
    // points list, because only the records carry the scaling and data format a tag type depends on.
    const pointSection = CONTROLLER_CONFIG_SECTIONS.find(section => section.key === 'points');
    this.controllerService.readConfigSection(this.deviceId, pointSection, false).subscribe({
      next: records => {
        this.points = (records || []).map(point => ({
          id: point.point_id,
          source: point.source,
          type: POINT_SOURCES.find(source => source.value === point.source)?.label,
          name: point.name,
          real: isRealPoint(point),
          bit: point.data_format === 6
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
    this.steerType(tag);
  }

  /** Gives a tag bound to a point the type that point's value needs; see {@link needsReal}. */
  steerType(tag: LogicTag): void {
    const needsReal = this.needsReal(tag);
    if (needsReal) {
      tag.dataType = 'REAL';
    } else if (needsReal === false && tag.dataType === 'REAL') {
      tag.dataType = this.boundPoint(tag).bit ? 'BOOL' : 'INT';
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
    const mismatch = this.program.tags.find(tag => {
      const needsReal = this.needsReal(tag);
      return needsReal !== null && needsReal !== (tag.dataType === 'REAL');
    });
    if (mismatch) {
      this.error = this.translate.instant(
        this.needsReal(mismatch) ? 'inferrix.logic-needs-real' : 'inferrix.logic-needs-whole',
        {tag: mismatch.name, point: mismatch.address});
      this.result = null;
      this.job = null;
      return;
    }
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

  /**
   * Whether a tag bound to a point must be REAL, or null when its type is free.
   *
   * The controller hands a bound tag the point's raw 32 bits, typed only by the tag: an INT tag on a
   * scaled point reads float bits as an integer, and a REAL tag on an unscaled one reads an integer as
   * float bits. Neither the platform's verifier nor the controller's catches it (firmware notes §26).
   * The one exception is a program driving a local AO through its point, where firmware 0.1.16
   * converts whatever type it is given.
   */
  private needsReal(tag: LogicTag): boolean {
    const point = this.boundPoint(tag);
    if (!point || (tag.cls === 'OUTPUT' && point.source === 3)) {
      return null;
    }
    return point.real;
  }

  private boundPoint(tag: LogicTag) {
    return tag.binding === 'ICC_POINT' ? this.points.find(point => point.id === tag.address) : undefined;
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
