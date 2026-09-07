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
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { concat, of } from 'rxjs';
import { catchError, toArray } from 'rxjs/operators';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';

/**
 * The controller's own diagnostics: what it can see of the network, what it is running, and two
 * probes it can run on request.
 *
 * The probes are the useful half. A controller that will not connect to the broker is the common
 * commissioning failure, and `mqtt/test` walks the same resolver and connect path the live client
 * uses, so it says which step actually fails rather than just "not connected".
 */
@Component({
  selector: 'tb-controller-diagnostics',
  templateUrl: './controller-diagnostics.component.html',
  styleUrls: ['./controller-diagnostics.component.scss'],
  standalone: false
})
export class ControllerDiagnosticsComponent implements OnInit {

  @Input() deviceId: string;
  @Input() readonly = false;

  network: any;
  memory: any;
  logic: any;
  firmware: any;

  loading = false;
  error: string;

  pingForm: UntypedFormGroup;
  pingResult: any;
  pingRunning = false;
  pingError: string;

  mqttTestResult: any;
  mqttTestRunning = false;
  mqttTestError: string;

  restartRunning = false;

  /** `state` values of GET /api/v1/logic/status. */
  readonly logicStates: {[state: number]: string} = {
    0: 'inferrix.logic-none',
    1: 'inferrix.logic-running',
    2: 'inferrix.logic-faulted',
    3: 'inferrix.logic-load-failed'
  };

  constructor(private fb: UntypedFormBuilder,
              private controllerService: InferrixControllerService) {}

  ngOnInit(): void {
    this.pingForm = this.fb.group({
      // The device rejects anything outside this charset with 400 bad_request; rejecting it here
      // keeps a typo from looking like a device fault.
      target: ['gateway', [Validators.required, Validators.pattern(/^[A-Za-z0-9.\-_]+$/)]],
      count: [3, [Validators.min(1), Validators.max(5)]],
      port: [null, [Validators.min(1), Validators.max(65535)]]
    });
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    // Sequential rather than concurrent: the firmware takes two clients at a time, so four parallel
    // reads just queue on the platform's per-device semaphore holding a request thread each.
    concat(
      this.get('/api/v1/diag/network'),
      this.get('/api/v1/diag/memory'),
      this.get('/api/v1/logic/status'),
      this.get('/api/v1/firmware/status')
    ).pipe(toArray()).subscribe(([network, memory, logic, firmware]) => {
      this.network = network;
      this.memory = memory;
      this.logic = logic;
      this.firmware = firmware;
      this.loading = false;
      if (!network && !memory && !logic && !firmware) {
        this.error = 'inferrix.controller-unreachable';
      }
    });
  }

  ping(): void {
    if (this.pingForm.invalid) {
      this.pingForm.markAllAsTouched();
      return;
    }
    const value = this.pingForm.value;
    const body: {[key: string]: any} = {target: value.target};
    if (value.count) {
      body.count = value.count;
    }
    if (value.port) {
      body.port = value.port;
    }
    this.pingRunning = true;
    this.pingError = null;
    this.pingResult = null;
    this.controllerService.proxy<any>(this.deviceId, 'POST', '/api/v1/diag/ping', body,
      {ignoreErrors: true}).subscribe({
      next: result => {
        this.pingResult = result;
        this.pingRunning = false;
      },
      error: error => {
        this.pingError = this.messageOf(error);
        this.pingRunning = false;
      }
    });
  }

  testMqtt(): void {
    this.mqttTestRunning = true;
    this.mqttTestError = null;
    this.mqttTestResult = null;
    // No overrides: testing the stored broker settings is the question being asked.
    this.controllerService.proxy<any>(this.deviceId, 'POST', '/api/v1/mqtt/test', {},
      {ignoreErrors: true}).subscribe({
      next: result => {
        this.mqttTestResult = result;
        this.mqttTestRunning = false;
      },
      error: error => {
        this.mqttTestError = this.messageOf(error);
        this.mqttTestRunning = false;
      }
    });
  }

  restartLogic(): void {
    this.restartRunning = true;
    this.controllerService.proxy(this.deviceId, 'POST', '/api/v1/logic/restart', null,
      {ignoreErrors: true}).subscribe({
      next: () => {
        this.restartRunning = false;
        this.reload();
      },
      error: error => {
        this.error = this.messageOf(error);
        this.restartRunning = false;
      }
    });
  }

  mqttTestSteps(): {name: string; step: any}[] {
    if (!this.mqttTestResult) {
      return [];
    }
    return ['dns', 'tcp', 'tls', 'connack', 'puback']
      .filter(name => this.mqttTestResult[name])
      .map(name => ({name, step: this.mqttTestResult[name]}));
  }

  private get(path: string) {
    return this.controllerService.proxy<any>(this.deviceId, 'GET', path, null, {ignoreErrors: true})
      .pipe(catchError(() => of(null)));
  }

  private messageOf(error: any): string {
    return error?.error?.message || error?.message || 'Request failed';
  }
}
