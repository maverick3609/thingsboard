// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayRuleSetDialogComponent,
  GatewayRuleSetDialogData } from '@home/pages/inferrix/gateway/gateway-rule-set-dialog.component';
import { GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';
import { GatewayCalendarRuleSet,
  gatewayCalendarRuleLabel } from '@shared/models/inferrix-gateway-schedule.models';

/**
 * Calendar rule sets — the named groups of dates a schedule makes exceptions for.
 *
 * A tab of their own rather than a dialog inside the schedule editor, because a rule set is shared:
 * "public holidays" is written once and every schedule that closes the plant on them points at the
 * same rows.
 */
@Component({
  selector: 'tb-gateway-rule-sets',
  templateUrl: './gateway-rule-sets.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayRuleSetsComponent extends GatewayListPanelComponent<GatewayCalendarRuleSet> {

  readonly displayedColumns = ['name', 'xid', 'rules', 'actions'];

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

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayCalendarRuleSet>> {
    return this.gatewayService.getRuleSets(this.deviceId, query, {ignoreLoading: true});
  }

  /** The first few rules as text, so a row says what it selects without being opened. */
  ruleSummary(row: GatewayCalendarRuleSet): string {
    const rules = Array.isArray(row?.rules) ? row.rules : [];
    const shown = rules.slice(0, 3).map(gatewayCalendarRuleLabel).join('; ');
    return rules.length > 3 ? `${shown}; …` : shown;
  }

  add(): void {
    this.open({rules: []}, this.translate.instant('inferrix.gateway.add-rule-set'));
  }

  edit(row: GatewayCalendarRuleSet): void {
    // The list row already carries the whole model on this stack -- unlike a data source, a rule
    // set has no detail route beyond the same shape -- so opening it needs no second call.
    this.open(row, row.name);
  }

  delete(row: GatewayCalendarRuleSet): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.delete-rule-set-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.delete-rule-set-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.deleteRuleSet(this.deviceId, row.xid, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }

  private open(ruleSet: GatewayCalendarRuleSet, title: string): void {
    this.dialog.open<GatewayRuleSetDialogComponent, GatewayRuleSetDialogData, GatewayCalendarRuleSet>(
      GatewayRuleSetDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {title, ruleSet, readonly: this.readonly}
      }).afterClosed().subscribe(saved => {
      if (saved) {
        this.gatewayService.saveRuleSet(this.deviceId, saved, {ignoreLoading: true}).subscribe({
          next: () => this.reload(),
          error: error => this.fail(error)
        });
      }
    });
  }
}
