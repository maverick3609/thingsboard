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

import { Component, forwardRef, Input, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import {
  ControlValueAccessor, FormBuilder, FormGroup, NG_VALIDATORS, NG_VALUE_ACCESSOR,
  ValidationErrors, Validator
} from '@angular/forms';
import { of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { EntityType } from '@shared/models/entity-type.models';
import { SchedulerEventConfiguration } from '@shared/models/scheduler-event.models';
import { ReportService } from '@core/http/report.service';
import { ReportConfig, ReportTemplateInfo } from '@shared/models/report.models';
import { ReportTemplateId } from '@shared/models/id/report-template-id';
import { PageLink } from '@shared/models/page/page-link';
import { emptyPageData } from '@shared/models/page/page-data';

@Component({
  selector: 'tb-scheduler-event-config',
  templateUrl: './scheduler-event-config.component.html',
  providers: [
    {provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SchedulerEventConfigComponent), multi: true},
    {provide: NG_VALIDATORS, useExisting: forwardRef(() => SchedulerEventConfigComponent), multi: true}
  ],
  standalone: false
})
export class SchedulerEventConfigComponent implements ControlValueAccessor, Validator, OnChanges, OnDestroy {

  @Input() eventType: string;

  entityType = EntityType;
  configFormGroup: FormGroup;
  attributeScopes = ['SERVER_SCOPE', 'SHARED_SCOPE'];

  // Report-template options for the 'report' mode picker (tb-entity-select can't list
  // REPORT_TEMPLATE - EntityService has no case for it - so this is a plain mat-select
  // backed directly by ReportService).
  reportTemplates: ReportTemplateInfo[] = [];
  private reportTemplatesLoaded = false;

  private propagateChange = (_v: any) => {};
  private valueChanges$: Subscription;

  constructor(private fb: FormBuilder, private reportService: ReportService) {
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
      rpcPersistent: [false],
      // generateReport helpers (bare ReportConfig shape - see updateModel's 'report' case)
      reportTemplateId: [null],   // plain uuid string; wrapped into a ReportTemplateId on emit
      userId: [null],             // full EntityId ({entityType:'USER', id}) via tb-entity-select
      timezone: [null],
      targets: [null],            // string[] of NOTIFICATION_TARGET uuids via tb-entity-list
      notificationTemplateId: [null]   // full EntityId via tb-template-autocomplete, or null
    });
    this.valueChanges$ = this.configFormGroup.valueChanges.subscribe(() => this.updateModel());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.eventType && this.mode === 'report' && !this.reportTemplatesLoaded) {
      this.loadReportTemplates();
    }
  }

  ngOnDestroy(): void { this.valueChanges$.unsubscribe(); }
  registerOnChange(fn: any): void { this.propagateChange = fn; }
  registerOnTouched(_fn: any): void {}

  // Originator is REQUIRED for the three typed dispatch modes (PE: originatorId:[null,[required]]
  // on the updateAttributes/sendRpcRequest/updateOtaPackage config forms). The 'raw' mode
  // (custom types + generateDashboardReport) has no originator picker and no requirement.
  // The 'report' mode (generateReport) has its own requirement: reportTemplateId/userId/timezone.
  // Returning an error here propagates invalidity up to the dialog's `configuration` control,
  // blocking save until the mode's requirements are met.
  validate(): ValidationErrors | null {
    if (this.mode === 'report') {
      const f = this.configFormGroup.getRawValue();
      if (!f.reportTemplateId || !f.userId || !f.timezone) {
        return {reportConfigRequired: true};
      }
      return null;
    }
    if (this.mode !== 'raw' && !this.configFormGroup.get('originatorId').value) {
      return {originatorRequired: true};
    }
    return null;
  }

  get mode(): 'attributes' | 'rpc' | 'ota' | 'report' | 'raw' {
    switch (this.eventType) {
      case 'updateAttributes': return 'attributes';
      case 'sendRpcRequest': return 'rpc';
      case 'updateFirmware':
      case 'updateSoftware': return 'ota';
      case 'generateReport': return 'report';
      default: return 'raw';   // custom types + generateDashboardReport (R2, not in R1 scope)
    }
  }

  get isReportType(): boolean {
    return this.eventType === 'generateReport' || this.eventType === 'generateDashboardReport';
  }

  private loadReportTemplates(): void {
    this.reportTemplatesLoaded = true;
    this.reportService.getReportTemplateInfos(new PageLink(100), {}, {ignoreLoading: true}).pipe(
      catchError(err => {
        console.error('[Reporting] Failed to load report templates', err);
        return of(emptyPageData<ReportTemplateInfo>());
      })
    ).subscribe(pageData => this.reportTemplates = pageData.data);
  }

  writeValue(value: SchedulerEventConfiguration | ReportConfig | null): void {
    if (this.mode === 'report') {
      // Bare ReportConfig shape (design spec §2.3) - NOT a SchedulerEventConfiguration; the
      // generateReport event's entire `configuration` node IS the ReportConfig (see
      // InferrixSchedulerReportExecutor#executeReport), so it never has msgBody/metadata.
      const v: Partial<ReportConfig> = (value as ReportConfig) || {};
      this.configFormGroup.patchValue({
        reportTemplateId: v.reportTemplateId?.id || null,
        userId: v.userId || null,
        timezone: v.timezone || null,
        targets: v.targets || null,
        notificationTemplateId: v.notificationTemplateId || null
      }, {emitEvent: false});
      return;
    }
    const v = (value as SchedulerEventConfiguration) || {msgType: null, msgBody: {}, metadata: null};
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
    let config: SchedulerEventConfiguration | ReportConfig;
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
      case 'report':
        // Bare ReportConfig - the whole configuration node, no originatorId/msgType/metadata
        // wrapper (see writeValue above / InferrixSchedulerReportExecutor#executeReport).
        config = {
          reportTemplateId: f.reportTemplateId ? new ReportTemplateId(f.reportTemplateId) : null,
          userId: f.userId || null,
          timezone: f.timezone || null,
          targets: f.targets || null,
          notificationTemplateId: f.notificationTemplateId || null
        };
        break;
      default:
        config = {originatorId: f.originatorId, msgType: f.msgType, msgBody: f.msgBody, metadata: f.metadata};
    }
    this.propagateChange(config);
  }
}
