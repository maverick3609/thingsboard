// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayScheduleDialogComponent,
  GatewayScheduleDialogData } from '@home/pages/inferrix/gateway/gateway-schedule-dialog.component';
import { GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { GatewayCalendarRuleSet, GatewaySchedule,
  gatewayWeek } from '@shared/models/inferrix-gateway-schedule.models';

/** How many rule sets the exception picker offers. A gateway has a handful, not a page of them. */
const RULE_SET_PICKER_LIMIT = 200;

/**
 * The gateway's schedules.
 *
 * A schedule is a weekly active/inactive pattern that set-point handlers and alert lists follow, so
 * this tab is where "the plant runs 08:00 to 17:00 on weekdays" is written down. The dates it makes
 * exceptions for come from the Rule sets tab.
 */
@Component({
  selector: 'tb-gateway-schedules',
  templateUrl: './gateway-schedules.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewaySchedulesComponent extends GatewayListPanelComponent<GatewaySchedule> {

  readonly displayedColumns = ['name', 'xid', 'active', 'days', 'enabled', 'actions'];

  /** Loaded once per panel activation, for the exception picker inside the dialog. */
  private ruleSets: GatewayCalendarRuleSet[] = [];

  constructor(private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  protected load(): void {
    // Not worth blocking the list on: without rule sets the dialog simply cannot add an exception,
    // and it says so.
    this.gatewayService.getRuleSets(this.deviceId,
      {pageSize: RULE_SET_PICKER_LIMIT, page: 0, sortProperty: 'name', sortOrder: 'ASC'},
      {ignoreLoading: true}).subscribe({
      next: page => {
        this.ruleSets = page?.items ?? [];
        this.cd.markForCheck();
      },
      error: () => {}
    });
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewaySchedule>> {
    return this.gatewayService.getSchedules(this.deviceId, query, {ignoreLoading: true});
  }

  /** How many of the seven days carry any change at all — the shape of the week at a glance. */
  activeDays(row: GatewaySchedule): number {
    return gatewayWeek(row?.defaultSchedule).filter(day => day.length > 0).length;
  }

  add(): void {
    this.open({enabled: false, alarmLevel: 'NONE', errorAlarmLevel: 'URGENT',
      defaultSchedule: gatewayWeek(null), exceptions: []},
      this.translate.instant('inferrix.gateway.add-schedule'));
  }

  edit(row: GatewaySchedule): void {
    this.gatewayService.getSchedule(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
    });
  }

  toggleEnabled(row: GatewaySchedule, enabled: boolean): void {
    this.gatewayService.setScheduleEnabled(this.deviceId, row.xid, enabled, {ignoreLoading: true})
      .subscribe({
        next: () => this.reload(),
        error: error => {
          this.fail(error);
          this.reload();
        }
      });
  }

  delete(row: GatewaySchedule): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-schedule-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-schedule-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteSchedule(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  private open(schedule: GatewaySchedule, title: string): void {
    this.dialog.open<GatewayScheduleDialogComponent, GatewayScheduleDialogData, GatewaySchedule>(
      GatewayScheduleDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, schedule, ruleSets: this.ruleSets, readonly: this.readonly}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveSchedule(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }
}
