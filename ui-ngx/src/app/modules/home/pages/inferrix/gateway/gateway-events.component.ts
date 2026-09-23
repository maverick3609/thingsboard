// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { gatewayAlarmTone, GatewayAlarmTone,
  GatewayEventInstance } from '@shared/models/inferrix-gateway-event.models';

/**
 * The event log — the records a detector actually raised.
 *
 * Distinct from the Alert routing tab, which decides who is told about them. The stack calls both
 * "alerts"; these are `EventInstanceVO`, and they are the ones that happened.
 *
 * **No free-text search here.** The events table has no `name` column and the platform turns a
 * search into `match(name, ...)`, which the gateway answers by refusing and listing every column
 * it does have. The service drops it rather than letting a shared search box break one tab.
 */
@Component({
  selector: 'tb-gateway-events',
  templateUrl: './gateway-events.component.html',
  styleUrls: ['../controller/controller-table.scss', './gateway-events.component.scss'],
  standalone: false
})
export class GatewayEventsComponent extends GatewayListPanelComponent<GatewayEventInstance> {

  readonly displayedColumns = ['alarmLevel', 'message', 'activeTimestamp', 'rtnTimestamp',
    'acknowledged', 'actions'];
  readonly toneOf = gatewayAlarmTone;

  constructor(private gatewayService: InferrixGatewayService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  /** Newest first: an event log read oldest-first shows the operator last month. */
  protected defaultSort(): {property: string; direction: 'ASC' | 'DESC'} {
    return {property: 'activeTimestamp', direction: 'DESC'};
  }

  protected load(): void {
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayEventInstance>> {
    return this.gatewayService.getEvents(this.deviceId, query, {ignoreLoading: true});
  }

  tone(row: GatewayEventInstance): GatewayAlarmTone {
    return this.toneOf(row?.alarmLevel);
  }

  /** Still active means the gateway has recorded no return to normal. */
  isActive(row: GatewayEventInstance): boolean {
    return !row?.rtnTimestamp;
  }

  canAcknowledge(row: GatewayEventInstance): boolean {
    return !this.readonly && !row?.acknowledgedTimestamp;
  }

  acknowledge(row: GatewayEventInstance): void {
    this.gatewayService.acknowledgeEvent(this.deviceId, row.id, {ignoreLoading: true}).subscribe({
      next: () => this.reload(),
      error: error => this.fail(error)
    });
  }

  acknowledgedBy(row: GatewayEventInstance): string {
    if (!row?.acknowledgedTimestamp) {
      return '';
    }
    // A gateway can acknowledge an event itself, in which case there is no username to show.
    return row.acknowledgedByUsername
      || this.translate.instant('inferrix.gateway.acknowledged-by-system');
  }
}
