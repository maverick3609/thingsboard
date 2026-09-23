// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayModelDialogComponent,
  GatewayModelDialogData } from '@home/pages/inferrix/gateway/gateway-model-dialog.component';
import { GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { GATEWAY_ALARM_LEVELS, GatewayAlertList } from '@shared/models/inferrix-gateway-event.models';
import { FormProperty, FormPropertyType } from '@shared/models/dynamic-form.models';

/**
 * Alert routing — **not** a list of alarms.
 *
 * An alert list is a notification preference: who is told, from which severity upwards, and when
 * they are not to be told. The raised records are on the Event log tab. The stack calls both
 * "alerts"; calling them both that here would confuse operators permanently, so the two tabs are
 * named for what they do.
 *
 * The form is hand-written, unlike every other model in this feature. `AlertListModel` has no
 * subtypes, so it is not a schema *family*, and the gateway's schema document only resolves the
 * components its families reference — so there is no published schema to render. Five fields and a
 * closed recipient table is a small thing to write by hand; the alternative is a stack ask for a
 * document that would describe exactly this.
 */
@Component({
  selector: 'tb-gateway-alert-lists',
  templateUrl: './gateway-alert-lists.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayAlertListsComponent extends GatewayListPanelComponent<GatewayAlertList> {

  readonly displayedColumns = ['name', 'receiveAlarmAlerts', 'xid', 'recipients', 'actions'];

  constructor(private gatewayService: InferrixGatewayService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  protected load(): void {
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayAlertList>> {
    return this.gatewayService.getAlertLists(this.deviceId, query, {ignoreLoading: true});
  }

  recipientCount(row: GatewayAlertList): number {
    return Array.isArray(row?.recipients) ? row.recipients.length : 0;
  }

  add(): void {
    this.open({receiveAlarmAlerts: 'INFORMATION', recipients: []},
      this.translate.instant('inferrix.gateway.add-alert-list'));
  }

  edit(row: GatewayAlertList): void {
    this.gatewayService.getAlertList(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
      next: full => this.open(full, full.name),
      error: error => this.fail(error)
    });
  }

  delete(row: GatewayAlertList): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-alert-list-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-alert-list-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteAlertList(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  private open(model: GatewayAlertList, title: string): void {
    this.dialog.open<GatewayModelDialogComponent, GatewayModelDialogData, GatewayAlertList>(
      GatewayModelDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          title,
          model,
          properties: this.properties(),
          recipientFields: ['recipients'],
          // 672 slots — seven days of quarter-hours. Nothing here edits it, and the dialog merges
          // the untouched model under the form's values so a save carries it through. Saying so is
          // the point: a dropped schedule does not look like a bug, it looks like the alert list
          // deciding to page someone at 03:00.
          carriedFields: model.inactiveSchedule
            ? [{id: 'inactiveSchedule',
                note: this.translate.instant('inferrix.gateway.inactive-schedule-carried')}]
            : [],
          readonly: this.readonly
        }
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveAlertList(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  /**
   * Built here rather than mapped from a schema, and every property is constructed — the same rule
   * the schema mapper follows, for the same reason: TB compiles `FormProperty.condition` to
   * executable JavaScript, so a FormProperty is code and only this file may author one.
   */
  private properties(): FormProperty[] {
    return [
      {
        id: 'receiveAlarmAlerts',
        name: this.translate.instant('inferrix.gateway.receive-alarm-alerts'),
        type: FormPropertyType.select,
        default: 'INFORMATION',
        required: true,
        hint: this.translate.instant('inferrix.gateway.receive-alarm-alerts-hint'),
        items: GATEWAY_ALARM_LEVELS.map(level => ({value: level, label: level}))
      } as FormProperty,
      {
        id: 'readPermissions',
        name: this.translate.instant('inferrix.gateway.read-permissions'),
        type: FormPropertyType.text,
        default: null,
        hint: this.translate.instant('inferrix.gateway.permissions-hint')
      } as FormProperty,
      {
        id: 'editPermissions',
        name: this.translate.instant('inferrix.gateway.edit-permissions'),
        type: FormPropertyType.text,
        default: null,
        hint: this.translate.instant('inferrix.gateway.permissions-hint')
      } as FormProperty
    ];
  }
}
