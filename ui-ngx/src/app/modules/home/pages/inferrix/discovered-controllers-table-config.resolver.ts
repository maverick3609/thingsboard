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

import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { DatePipe } from '@angular/common';
import { TranslateService } from '@ngx-translate/core';
import { select, Store } from '@ngrx/store';
import { Observable, of } from 'rxjs';
import { catchError, map, take, tap } from 'rxjs/operators';
import {
  checkBoxCell,
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityTypeResource } from '@shared/models/entity-type.models';
import { AppState } from '@core/core.state';
import { selectAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { Direction } from '@shared/models/page/sort-order';
import { InferrixControllerService } from '@core/http/inferrix-controller.service';
import { DiscoveredControllerRow, escapeCell } from '@shared/models/inferrix-controller.models';
import { AdoptControllerDialogComponent } from '@home/pages/inferrix/adopt-controller-dialog.component';
import { AssignControllerDialogComponent } from '@home/pages/inferrix/assign-controller-dialog.component';

/**
 * Controllers that have announced themselves and belong to nobody yet.
 *
 * This is the only way to see a controller that has never been adopted: over MQTT a device must
 * already hold platform credentials to connect at all, so MQTT can only ever show controllers that
 * were adopted before. The firmware's plain-TCP announce exists precisely to cover that gap.
 *
 * A system administrator sees every announce on the network. A tenant administrator sees only what
 * a system administrator has assigned to them — an unadopted controller has no tenant of its own,
 * and showing the raw list to everyone would hand each tenant the addresses, deployment names and
 * locations of every other tenant's hardware on the same network.
 *
 * Nothing here is a platform entity yet, so the table is read-only in the audit-log sense: no
 * details panel, no selection, no delete. The sightings are held in memory on the node and are not
 * paged server-side, so the page link is applied to the list the platform returns.
 */
@Injectable()
export class DiscoveredControllersTableConfigResolver {

  private readonly config: EntityTableConfig<DiscoveredControllerRow> =
    new EntityTableConfig<DiscoveredControllerRow>();

  constructor(private store: Store<AppState>,
              private controllerService: InferrixControllerService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private router: Router) {

    this.config.tableTitle = this.translate.instant('inferrix.discovered-controllers');
    // Nothing here is a platform entity, so the strings the table would normally take from the
    // entity type are supplied directly, the way the audit log does it.
    this.config.entityTranslations = {
      noEntities: 'inferrix.no-discovered-controllers',
      search: 'inferrix.search-discovered-controllers'
    };
    this.config.entityResources = {} as EntityTypeResource<DiscoveredControllerRow>;
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = false;
    this.config.addEnabled = false;
    this.config.entitiesDeleteEnabled = false;
    this.config.searchEnabled = true;
    this.config.defaultSortOrder = {property: 'lastSeenTs', direction: Direction.DESC};

    this.config.columns.push(
      new EntityTableColumn<DiscoveredControllerRow>('uid', 'inferrix.uid', '25%',
        controller => escapeCell(controller.uid)),
      new EntityTableColumn<DiscoveredControllerRow>('ip', 'inferrix.address', '15%',
        controller => escapeCell(controller.ip)),
      new EntityTableColumn<DiscoveredControllerRow>('identity', 'inferrix.identity', '30%',
        controller => this.identityLabel(controller), () => ({}), false),
      new DateEntityTableColumn<DiscoveredControllerRow>('lastSeenTs', 'inferrix.last-seen', this.datePipe, '150px'),
      new EntityTableColumn<DiscoveredControllerRow>('announceCount', 'inferrix.announces', '80px',
        controller => escapeCell(controller.announceCount))
    );

    this.config.entitiesFetchFunction = pageLink => this.fetchDiscovered(pageLink);
  }

  resolve(): Observable<EntityTableConfig<DiscoveredControllerRow>> {
    return this.store.pipe(select(selectAuthUser), take(1)).pipe(
      tap(authUser => {
        const isSysAdmin = authUser.authority === Authority.SYS_ADMIN;
        this.config.columns = this.config.columns.filter(column => column.key !== 'assigned');
        if (isSysAdmin) {
          this.config.columns.push(
            new EntityTableColumn<DiscoveredControllerRow>('assigned', 'inferrix.assigned', '80px',
              controller => checkBoxCell(!!controller.assignedTenantId), () => ({}), false));
        }
        this.config.cellActionDescriptors = isSysAdmin
          ? [{
              name: this.translate.instant('inferrix.assign'),
              icon: 'assignment_ind',
              isEnabled: () => true,
              onAction: ($event, controller) => this.assign($event, controller)
            }]
          : [{
              name: this.translate.instant('inferrix.adopt'),
              icon: 'add_circle_outline',
              isEnabled: () => true,
              onAction: ($event, controller) => this.adopt($event, controller)
            }];
        this.config.headerActionDescriptors = isSysAdmin ? [] : [{
          name: this.translate.instant('inferrix.adopt-by-address'),
          icon: 'add',
          isEnabled: () => true,
          onAction: $event => this.adopt($event, null)
        }];
      }),
      map(() => this.config)
    );
  }

  /**
   * Sightings live in the node's memory, so the whole list comes back at once and the page link is
   * applied here. Bounded by what a network can physically announce, not by tenant data volume.
   */
  private fetchDiscovered(pageLink: PageLink): Observable<PageData<DiscoveredControllerRow>> {
    return this.controllerService.getDiscoveredControllers({ignoreErrors: true}).pipe(
      catchError(() => of([])),
      map(discovered => pageLink.filterData(
        discovered.map(controller => ({...controller, id: {id: controller.uid}}) as DiscoveredControllerRow)))
    );
  }

  private identityLabel(controller: DiscoveredControllerRow): string {
    const identity = controller.identity || {};
    return [identity.name, identity.location, identity.model]
      .filter(value => !!value).map(escapeCell).join(' · ');
  }

  private assign($event: MouseEvent, controller: DiscoveredControllerRow): void {
    $event?.stopPropagation();
    this.dialog.open<AssignControllerDialogComponent, any, any>(AssignControllerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {controller}
    }).afterClosed().subscribe(assigned => {
      if (assigned) {
        this.config.updateData();
      }
    });
  }

  private adopt($event: MouseEvent, controller: DiscoveredControllerRow): void {
    $event?.stopPropagation();
    this.dialog.open<AdoptControllerDialogComponent, any, any>(AdoptControllerDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data: {controller}
    }).afterClosed().subscribe(device => {
      if (device) {
        this.router.navigateByUrl(`/controllers/${device.id.id}`);
      }
    });
  }

}
