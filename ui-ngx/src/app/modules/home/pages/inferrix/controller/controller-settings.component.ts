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

import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { from, interval, Observable, of, Subject, Subscription } from 'rxjs';
import { catchError, concatMap, finalize, takeUntil, tap } from 'rxjs/operators';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { ControllerSettingField, ControllerSettingsForm } from '@shared/models/inferrix-controller.models';

/**
 * The controller's settings endpoints are all one shape — GET a flat JSON object, edit it, PUT it
 * back — so they are described by {@link CONTROLLER_SETTINGS_FORMS} rather than written out five
 * times. Only what genuinely is not that shape gets real code: the network confirm-or-revert window
 * below, and the peer table, which is a list rather than a form and is not built yet.
 */
@Component({
  selector: 'tb-controller-settings',
  templateUrl: './controller-settings.component.html',
  styleUrls: ['./controller-settings.component.scss'],
  standalone: false
})
export class ControllerSettingsComponent implements OnInit, OnDestroy {

  /** The device's masked-secret sentinel: sending it back leaves the stored value untouched. */
  static readonly MASKED = '***';

  @Input() deviceId: string;
  @Input() readonly = false;

  readonly forms = CONTROLLER_SETTINGS_FORMS;

  formGroups: {[path: string]: UntypedFormGroup} = {};
  loading: {[path: string]: boolean} = {};
  errors: {[path: string]: string} = {};
  saved: {[path: string]: boolean} = {};

  /**
   * Seconds left to confirm a network change before the controller reverts it.
   *
   * A PUT to /network applies the new addressing live but does not persist it: the platform has to
   * reach the device again — on the new address, which may well be a different one — and confirm
   * within 120 seconds, or the controller puts the old configuration back. That is a deliberate
   * lockout guard in the firmware, and the countdown exists so the operator can see it running.
   */
  confirmSecondsLeft = 0;
  confirmPending = false;
  private confirmTimer: Subscription;
  private destroy$ = new Subject<void>();

  constructor(private fb: UntypedFormBuilder,
              private controllerService: InferrixControllerService) {}

  ngOnInit(): void {
    this.forms.forEach(form => {
      this.formGroups[form.path] = this.fb.group(
        Object.fromEntries(form.fields.map(field => [field.key, [null, this.validatorsFor(field)]])));
      if (this.readonly) {
        this.formGroups[form.path].disable();
      }
      this.loading[form.path] = true;
    });
    // One at a time: the firmware serves two clients at once and makes the rest wait, so firing all
    // five reads together only queues them somewhere less visible.
    from(this.forms).pipe(concatMap(form => this.read(form)), takeUntil(this.destroy$)).subscribe();
  }

  ngOnDestroy(): void {
    this.stopConfirmWindow();
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(form: ControllerSettingsForm): void {
    this.loading[form.path] = true;
    this.read(form).subscribe();
  }

  /** Reads one settings object into its form. Never errors, so a chained read is not cut short. */
  private read(form: ControllerSettingsForm): Observable<any> {
    this.errors[form.path] = null;
    return this.controllerService.proxy<any>(this.deviceId, 'GET', form.path, null,
      {ignoreErrors: true}).pipe(
      tap(value => {
        this.formGroups[form.path].patchValue(value ?? {}, {emitEvent: false});
        this.formGroups[form.path].markAsPristine();
        if (form.path === NETWORK_PATH) {
          this.confirmPending = !!value?.confirm_pending;
        }
      }),
      catchError(error => {
        this.errors[form.path] = this.messageOf(error);
        return of(null);
      }),
      finalize(() => this.loading[form.path] = false));
  }

  save(form: ControllerSettingsForm): void {
    const group = this.formGroups[form.path];
    if (group.invalid) {
      group.markAllAsTouched();
      return;
    }
    this.loading[form.path] = true;
    this.errors[form.path] = null;
    this.saved[form.path] = false;
    this.controllerService.proxy<any>(this.deviceId, 'PUT', form.path, this.payload(form),
      {ignoreErrors: true}).subscribe({
      next: response => {
        this.loading[form.path] = false;
        this.saved[form.path] = true;
        group.markAsPristine();
        if (form.path === NETWORK_PATH && response?.confirm_required) {
          this.startConfirmWindow(response.window_s ?? 120);
        }
      },
      error: error => {
        this.errors[form.path] = this.messageOf(error);
        this.loading[form.path] = false;
      }
    });
  }

  confirmNetwork(): void {
    this.controllerService.proxy(this.deviceId, 'POST', '/api/v1/network/confirm', null,
      {ignoreErrors: true}).subscribe({
      next: () => {
        this.stopConfirmWindow();
        this.confirmPending = false;
      },
      error: error => this.errors[NETWORK_PATH] = this.messageOf(error)
    });
  }

  /**
   * Secrets the device masks are only sent when the operator actually typed something new. Sending
   * the mask back would be harmless — the firmware reads it as "keep what is stored" — but leaving
   * the field out entirely means a save never depends on that behaviour being right.
   */
  private payload(form: ControllerSettingsForm): {[key: string]: any} {
    const value = this.formGroups[form.path].getRawValue();
    const body: {[key: string]: any} = {};
    form.fields.forEach(field => {
      const current = value[field.key];
      if (current === null || current === undefined || current === '') {
        return;
      }
      if (field.writeOnly && current === ControllerSettingsComponent.MASKED) {
        return;
      }
      body[field.key] = current;
    });
    return body;
  }

  private startConfirmWindow(windowSeconds: number): void {
    this.stopConfirmWindow();
    this.confirmPending = true;
    this.confirmSecondsLeft = windowSeconds;
    this.confirmTimer = interval(1000).subscribe(() => {
      this.confirmSecondsLeft--;
      if (this.confirmSecondsLeft <= 0) {
        // The window closed. Whether the device reverted or the confirm landed is not knowable from
        // here, so re-read rather than guess.
        this.stopConfirmWindow();
        this.load(this.forms.find(f => f.path === NETWORK_PATH));
      }
    });
  }

  private stopConfirmWindow(): void {
    this.confirmTimer?.unsubscribe();
    this.confirmTimer = null;
    this.confirmSecondsLeft = 0;
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
    return validators;
  }

  private messageOf(error: any): string {
    return error?.error?.message || error?.message || 'Request failed';
  }
}

const NETWORK_PATH = '/api/v1/network';

/** Field limits mirror docs/INTEGRATION-API.md §3.3 in the controller repo. */
export const CONTROLLER_SETTINGS_FORMS: ControllerSettingsForm[] = [
  {
    path: '/api/v1/identity',
    titleKey: 'inferrix.settings-identity',
    fields: [
      {key: 'name', label: 'inferrix.deployment-name', type: 'text', maxLength: 31},
      {key: 'location', label: 'inferrix.location', type: 'text', maxLength: 63}
    ]
  },
  {
    path: NETWORK_PATH,
    titleKey: 'inferrix.settings-network',
    fields: [
      {key: 'mode', label: 'inferrix.network-mode', type: 'select',
        options: [{value: 'dhcp', label: 'DHCP'}, {value: 'static', label: 'Static'}]},
      {key: 'ip', label: 'inferrix.network-ip', type: 'text'},
      {key: 'mask', label: 'inferrix.network-mask', type: 'text'},
      {key: 'gw', label: 'inferrix.network-gateway', type: 'text'},
      {key: 'dns', label: 'inferrix.network-dns', type: 'text', hint: 'inferrix.network-dns-hint'}
    ]
  },
  {
    path: '/api/v1/mqtt',
    titleKey: 'inferrix.settings-mqtt',
    fields: [
      {key: 'enabled', label: 'inferrix.mqtt-enabled', type: 'boolean'},
      {key: 'host', label: 'inferrix.mqtt-host', type: 'text', maxLength: 63,
        hint: 'inferrix.mqtt-host-hint'},
      {key: 'port', label: 'inferrix.mqtt-port', type: 'number', min: 1, max: 65535},
      {key: 'username', label: 'inferrix.mqtt-username', type: 'text', maxLength: 31},
      {key: 'password', label: 'inferrix.mqtt-password', type: 'password', maxLength: 31,
        writeOnly: true, hint: 'inferrix.masked-hint'},
      {key: 'client_id', label: 'inferrix.mqtt-client-id', type: 'text', maxLength: 31},
      {key: 'base_topic', label: 'inferrix.mqtt-base-topic', type: 'text', maxLength: 47},
      {key: 'keepalive_s', label: 'inferrix.mqtt-keepalive', type: 'number', min: 1, max: 65535},
      {key: 'tls_on', label: 'inferrix.mqtt-tls', type: 'boolean'},
      {key: 'ca_cert', label: 'inferrix.mqtt-ca-cert', type: 'textarea', maxLength: 2047,
        writeOnly: true, hint: 'inferrix.masked-hint'},
      {key: 'health_interval_s', label: 'inferrix.mqtt-health-interval', type: 'number',
        min: 0, max: 65535, hint: 'inferrix.mqtt-health-interval-hint'}
    ]
  },
  {
    path: '/api/v1/discovery',
    titleKey: 'inferrix.settings-discovery',
    fields: [
      {key: 'host', label: 'inferrix.discovery-host', type: 'text', maxLength: 63,
        hint: 'inferrix.discovery-host-hint'},
      {key: 'port', label: 'inferrix.discovery-port', type: 'number', min: 1, max: 65535},
      {key: 'period_s', label: 'inferrix.discovery-period', type: 'number', min: 1, max: 65535}
    ]
  },
  {
    path: '/api/v1/time',
    titleKey: 'inferrix.settings-time',
    fields: [
      {key: 'retry_interval_s', label: 'inferrix.time-retry', type: 'number', min: 1},
      {key: 'resync_interval_s', label: 'inferrix.time-resync', type: 'number', min: 1,
        hint: 'inferrix.time-resync-hint'}
    ]
  }
];
