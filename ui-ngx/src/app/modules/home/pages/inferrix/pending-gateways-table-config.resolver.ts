// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityTypeResource } from '@shared/models/entity-type.models';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { Direction } from '@shared/models/page/sort-order';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { escapeCell } from '@shared/models/inferrix-controller.models';
import { isAdoptable, PendingGateway } from '@shared/models/inferrix-gateway.models';
import { AdoptGatewayDialogComponent } from '@home/pages/inferrix/adopt-gateway-dialog.component';

/** A pending row as the table needs it: the id is the device's, since the device already exists. */
interface PendingGatewayRow extends PendingGateway {
  id: {id: string};
}

/**
 * Gateways that have provisioned themselves but that nobody has adopted.
 *
 * Unlike the controller's discovery list these *are* real devices already — a gateway provisions
 * itself over MQTT, which creates the device — so what is missing is not the entity but the
 * platform's side of the relationship: a confirmed management address and a sealed API token.
 *
 * Follows the audit-log table pattern: no details panel, no selection, no delete. A table with no
 * `entityType` must set `entityTranslations` and `entityResources` by hand or it throws on its
 * empty state.
 */
@Injectable()
export class PendingGatewaysTableConfigResolver {

  private readonly config: EntityTableConfig<PendingGatewayRow> =
    new EntityTableConfig<PendingGatewayRow>();

  constructor(private gatewayService: InferrixGatewayService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private router: Router) {

    this.config.tableTitle = this.translate.instant('inferrix.gateway.pending');
    this.config.entityTranslations = {
      noEntities: 'inferrix.gateway.no-pending',
      search: 'inferrix.gateway.search-pending'
    };
    this.config.entityResources = {} as EntityTypeResource<PendingGatewayRow>;
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = false;
    this.config.addEnabled = false;
    this.config.entitiesDeleteEnabled = false;
    this.config.searchEnabled = true;
    this.config.defaultSortOrder = {property: 'createdTime', direction: Direction.DESC};

    this.config.columns.push(
      new EntityTableColumn<PendingGatewayRow>('name', 'device.name', '40%',
        row => escapeCell(row.name)),
      // The gateway's own claim about where it is, published over MQTT. Escaped for the same
      // reason every other device-reported cell is: the table renders through
      // bypassSecurityTrustHtml and this string is written by the device.
      new EntityTableColumn<PendingGatewayRow>('reportedAddress', 'inferrix.gateway.reported-address', '30%',
        row => escapeCell(row.reportedAddress)),
      new DateEntityTableColumn<PendingGatewayRow>('createdTime', 'common.created-time', this.datePipe, '150px')
    );

    this.config.cellActionDescriptors = [{
      name: this.translate.instant('inferrix.gateway.adopt'),
      icon: 'add_circle_outline',
      // A row with no reported address still opens the form -- the operator can see the device on
      // their own network and type it -- so the action is never disabled, only prefilled or not.
      isEnabled: () => true,
      onAction: ($event, row) => this.adopt($event, row)
    }];

    this.config.entitiesFetchFunction = pageLink => this.fetchPending(pageLink);
  }

  resolve(): Observable<EntityTableConfig<PendingGatewayRow>> {
    return of(this.config);
  }

  /**
   * The platform returns the whole pending set in one call — it is bounded by how many gateways a
   * tenant has waiting, not by data volume — so the page link is applied here.
   */
  private fetchPending(pageLink: PageLink): Observable<PageData<PendingGatewayRow>> {
    return this.gatewayService.getPendingGateways({ignoreErrors: true}).pipe(
      catchError(() => of([] as PendingGateway[])),
      map(pending => pageLink.filterData(
        pending.map(row => ({...row, id: {id: row.deviceId.id}}) as PendingGatewayRow)))
    );
  }

  private adopt($event: MouseEvent, row: PendingGatewayRow): void {
    $event?.stopPropagation();
    this.dialog.open<AdoptGatewayDialogComponent, any, any>(AdoptGatewayDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {pending: isAdoptable(row) ? row : {...row, reportedAddress: null}}
    }).afterClosed().subscribe(device => {
      if (device) {
        // Adopted gateways belong on the main list, not here, and the row has just left this one.
        this.router.navigateByUrl('/entities/gateways');
      }
    });
  }
}
