// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { Observable } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { DialogService } from '@core/services/dialog.service';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayListPanelComponent } from '@home/pages/inferrix/gateway/gateway-list-panel.component';
import { GatewayDataSource, GatewayDeviceProfile, GatewayListQuery,
  GatewayPage } from '@shared/models/inferrix-gateway-data.models';

export type GatewayProvisioningView = 'unprovisioned' | 'provisioned';

/**
 * Which of the gateway's data sources are published to the platform as devices.
 *
 * One data source becomes one device, and that is not a presentation choice: the publisher tags
 * every message with the data point's `deviceName` and then labels the whole batch with the *first*
 * message's name, so a publisher spanning two data sources would file both under whichever point
 * happened to be first. The gateway's own provisioning therefore builds one publisher per data
 * source, and this tab drives that rather than reimplementing it.
 *
 * The two lists are the gateway's bookkeeping, not a guess: a data source is "provisioned" when an
 * `IntegrationMappingData` row ties it to a publisher and a device. A hand-built publisher will
 * publish telemetry perfectly well and still show as unprovisioned here, because nothing recorded
 * the link — and unprovisioning would then not find it.
 */
@Component({
  selector: 'tb-gateway-provisioning',
  templateUrl: './gateway-provisioning.component.html',
  styleUrls: ['../controller/controller-table.scss'],
  standalone: false
})
export class GatewayProvisioningComponent extends GatewayListPanelComponent<GatewayDataSource> {

  view: GatewayProvisioningView = 'unprovisioned';

  profiles: GatewayDeviceProfile[] = [];
  profilesLoading = false;

  /**
   * The profile chosen for each unprovisioned row, keyed by data source xid.
   *
   * Deliberately empty until an operator picks one, with the action disabled meanwhile. A default
   * here would be a silent guess at what a device *is*, and correcting it afterwards means
   * deleting the device on the platform — a connect never changes an existing device's profile.
   */
  readonly chosenProfile = new Map<string, number>();

  /** Rows queued this session, so a row that answered 200 does not look untouched for a minute. */
  readonly queued = new Set<string>();

  constructor(private gatewayService: InferrixGatewayService,
              private dialogService: DialogService,
              private translate: TranslateService,
              cd: ChangeDetectorRef) {
    super(cd);
  }

  get displayedColumns(): string[] {
    return this.view === 'unprovisioned'
      ? ['name', 'modelType', 'profile', 'actions']
      : ['name', 'modelType', 'xid', 'actions'];
  }

  protected load(): void {
    this.loadProfiles(false);
    this.reload();
  }

  protected fetch(query: GatewayListQuery): Observable<GatewayPage<GatewayDataSource>> {
    return this.view === 'unprovisioned'
      ? this.gatewayService.getUnprovisionedDataSources(this.deviceId, query, {ignoreLoading: true})
      : this.gatewayService.getProvisionedDataSources(this.deviceId, query, {ignoreLoading: true});
  }

  switchView(view: GatewayProvisioningView): void {
    if (this.view === view) {
      return;
    }
    this.view = view;
    this.pageIndex = 0;
    this.reload();
  }

  /**
   * @param sync re-read the platform's profiles into the gateway's copy first. That write is
   *             admin-only, so it is an explicit action and never part of opening the tab.
   */
  loadProfiles(sync: boolean): void {
    this.profilesLoading = true;
    const query: GatewayListQuery = {pageSize: 100, page: 0, sortProperty: 'name', sortOrder: 'ASC'};
    const request = sync
      ? this.gatewayService.syncGatewayDeviceProfiles(this.deviceId, query, {ignoreLoading: true})
      : this.gatewayService.getGatewayDeviceProfiles(this.deviceId, query, {ignoreLoading: true});
    request.subscribe({
      next: page => {
        this.profiles = page?.items ?? [];
        this.profilesLoading = false;
        this.cd.markForCheck();
      },
      error: error => {
        this.profilesLoading = false;
        this.fail(error);
      }
    });
  }

  chooseProfile(row: GatewayDataSource, profileId: number): void {
    this.chosenProfile.set(row.xid, profileId);
  }

  profileFor(row: GatewayDataSource): number {
    return this.chosenProfile.get(row.xid);
  }

  isQueued(row: GatewayDataSource): boolean {
    return this.queued.has(row.xid);
  }

  canProvision(row: GatewayDataSource): boolean {
    return !this.readonly && !!this.profileFor(row) && !this.isQueued(row);
  }

  /**
   * The gateway answers as soon as it has *queued* the work, and drains that queue one data source
   * per minute. So this reports "queued", never "provisioned", and leaves the row in place — the
   * list only moves it once the gateway's own bookkeeping says so.
   */
  provision(row: GatewayDataSource): void {
    const profileId = this.profileFor(row);
    if (!profileId) {
      return;
    }
    this.queued.add(row.xid);
    this.cd.markForCheck();
    this.gatewayService.provisionDataSource(this.deviceId, row.xid, profileId,
      {ignoreLoading: true}).subscribe({
      next: () => this.cd.markForCheck(),
      error: error => {
        this.queued.delete(row.xid);
        this.fail(error);
      }
    });
  }

  /**
   * Deletes the publisher feeding this device, so telemetry stops. The device may survive on the
   * platform: the gateway records no platform device id and cannot always remove it.
   */
  unprovision(row: GatewayDataSource): void {
    // The name goes in the TITLE, which ConfirmDialogComponent interpolates. Its MESSAGE is
    // rendered with [innerHTML] through the `safe` pipe, which bypasses Angular's sanitiser --
    // so a gateway-supplied name must never be moved into the second argument.
    this.dialogService.confirm(
      this.translate.instant('inferrix.gateway.unprovision-title', {name: row.name}),
      this.translate.instant('inferrix.gateway.unprovision-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.gatewayService.unprovisionDataSource(this.deviceId, row.id, {ignoreLoading: true})
          .subscribe({
            next: () => this.reload(),
            error: error => this.fail(error)
          });
      }
    });
  }
}
