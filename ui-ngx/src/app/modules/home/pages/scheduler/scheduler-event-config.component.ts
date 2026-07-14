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

import { Component, forwardRef, Input, OnDestroy } from '@angular/core';
import {
  ControlValueAccessor, FormBuilder, FormGroup, NG_VALIDATORS, NG_VALUE_ACCESSOR,
  ValidationErrors, Validator
} from '@angular/forms';
import { Subscription } from 'rxjs';
import { EntityType } from '@shared/models/entity-type.models';
import { SchedulerEventConfiguration } from '@shared/models/scheduler-event.models';

@Component({
  selector: 'tb-scheduler-event-config',
  templateUrl: './scheduler-event-config.component.html',
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SchedulerEventConfigComponent), multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => SchedulerEventConfigComponent), multi: true}
  ],
  standalone: false
})
export class SchedulerEventConfigComponent implements ControlValueAccessor, Validator, OnDestroy {

  @Input() eventType: string;

  entityType = EntityType;
  configFormGroup: FormGroup;
  attributeScopes = ['SERVER_SCOPE', 'SHARED_SCOPE'];

  private propagateChange = (_v: any) => {};
  private valueChanges$: Subscription;

  constructor(private fb: FormBuilder) {
    this.configFormGroup = this.fb.group({
      // originatorId is a TRANSIENT UI-only field: the per-type picker (template below)
      // writes it here, and the DIALOG lifts it out to the top-level
      // SchedulerEventInfo.originatorId bean field (deleting it from configuration)
      // before POST. The engine fires to that bean field; the persisted configuration
      // JSON never contains originatorId. See spec §2 "UI originator flow" (PE-faithful).
      originatorId: [null],
      msgType: [null],
      msgBody: [{}],
      metadata: [null],
      // updateAttributes helpers
      attributesScope: ['SERVER_SCOPE'],
      attributes: [{}],
      // sendRpcRequest helpers
      rpcMethod: [null],
      rpcParams: [{}],
      rpcOneway: [false],
      rpcTimeout: [30000],
      rpcPersistent: [false]
    });
    this.valueChanges$ = this.configFormGroup.valueChanges.subscribe(() => this.updateModel());
  }

  ngOnDestroy(): void { this.valueChanges$.unsubscribe(); }
  registerOnChange(fn: any): void { this.propagateChange = fn; }
  registerOnTouched(_fn: any): void {}

  // Originator is REQUIRED for the three typed dispatch modes (PE: originatorId:[null,[required]]
  // on the updateAttributes/sendRpcRequest/updateOtaPackage config forms). The 'raw' mode
  // (custom types + generateReport/generateDashboardReport) has no originator picker and no
  // requirement. Returning an error here propagates invalidity up to the dialog's
  // `configuration` control, blocking save until an originator is chosen.
  validate(): ValidationErrors | null {
    if (this.mode !== 'raw' && !this.configFormGroup.get('originatorId').value) {
      return {originatorRequired: true};
    }
    return null;
  }

  get mode(): 'attributes' | 'rpc' | 'ota' | 'raw' {
    switch (this.eventType) {
      case 'updateAttributes': return 'attributes';
      case 'sendRpcRequest': return 'rpc';
      case 'updateFirmware':
      case 'updateSoftware': return 'ota';
      default: return 'raw';   // custom types + generateReport/generateDashboardReport (until Reporting lands)
    }
  }

  get isReportType(): boolean {
    return this.eventType === 'generateReport' || this.eventType === 'generateDashboardReport';
  }

  writeValue(value: SchedulerEventConfiguration | null): void {
    const v = value || {msgType: null, msgBody: {}, metadata: null};
    this.configFormGroup.patchValue({
      originatorId: v.originatorId || null,
      msgType: v.msgType || null,
      msgBody: v.msgBody || {},
      metadata: v.metadata || null,
      attributesScope: v.metadata?.scope || 'SERVER_SCOPE',
      attributes: this.mode === 'attributes' ? (v.msgBody || {}) : {},
      rpcMethod: this.mode === 'rpc' ? (v.msgBody?.method || null) : null,
      rpcParams: this.mode === 'rpc' ? (v.msgBody?.params || {}) : {},
      rpcOneway: v.metadata?.oneway === 'true',
      rpcTimeout: v.metadata?.timeout ? Number(v.metadata.timeout) : 30000,
      rpcPersistent: v.metadata?.persistent === 'true'
    }, {emitEvent: false});
  }

  private updateModel(): void {
    const f = this.configFormGroup.getRawValue();
    let config: SchedulerEventConfiguration;
    switch (this.mode) {
      case 'attributes':
        config = {
          originatorId: f.originatorId,
          msgType: null,
          msgBody: f.attributes,
          metadata: {scope: f.attributesScope}
        };
        break;
      case 'rpc':
        config = {
          originatorId: f.originatorId,
          msgType: null,
          msgBody: {method: f.rpcMethod, params: f.rpcParams},
          metadata: {
            oneway: String(f.rpcOneway),
            timeout: String(f.rpcTimeout),
            persistent: String(f.rpcPersistent)
          }
        };
        break;
      case 'ota':
        config = {
          originatorId: f.originatorId,
          msgType: null,
          msgBody: f.msgBody,   // {entityType: 'OTA_PACKAGE', id: ...} — raw editor in v1
          metadata: null
        };
        break;
      default:
        config = {originatorId: f.originatorId, msgType: f.msgType, msgBody: f.msgBody, metadata: f.metadata};
    }
    this.propagateChange(config);
  }
}
