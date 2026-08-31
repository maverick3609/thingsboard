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
import { ActivatedRouteSnapshot } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { select, Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { selectAuthUser } from '@core/auth/auth.selectors';
import { take } from 'rxjs/operators';
import { Observable } from 'rxjs';
import {
  CellActionDescriptor,
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { Authority } from '@shared/models/authority.enum';
import {
  SchedulerEvent,
  schedulerEventConfigTypes,
  schedulerEventStatus,
  SchedulerEventStatus,
  schedulerEventStatusTranslations,
  SchedulerEventWithCustomerInfo
} from '@shared/models/scheduler-event.models';
import { SchedulerEventService } from '@core/http/scheduler-event.service';
import {
  SchedulerEventDialogComponent,
  SchedulerEventDialogData
} from '@home/pages/scheduler/scheduler-event-dialog.component';

@Injectable()
export class SchedulerEventsTableConfigResolver {

  private readonly config: EntityTableConfig<SchedulerEventWithCustomerInfo> =
    new EntityTableConfig<SchedulerEventWithCustomerInfo>();

  constructor(private schedulerEventService: SchedulerEventService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private store: Store<AppState>) {

    this.config.entityType = EntityType.SCHEDULER_EVENT;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.SCHEDULER_EVENT);
    this.config.entityResources = entityTypeResources.get(EntityType.SCHEDULER_EVENT);
    this.config.tableTitle = this.translate.instant('scheduler.scheduler');
    this.config.detailsPanelEnabled = false;
    this.config.entityTitle = (event) => event ? event.name : '';

    this.config.columns.push(
      new DateEntityTableColumn<SchedulerEventWithCustomerInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('name', 'scheduler.name', '25%'),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('type', 'scheduler.event-type', '25%',
        entity => this.typeLabel(entity.type)),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('customerTitle', 'scheduler.created_customer', '25%'),
      // Not sortable: 'enabled' is a real column but the rendered value also folds in schedule expiry,
      // which the backend cannot order by.
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('enabled', 'scheduler.status', '120px',
        entity => this.translate.instant(schedulerEventStatusTranslations.get(this.statusOf(entity))),
        entity => this.statusOf(entity) === SchedulerEventStatus.EXPIRED ? {color: 'rgb(221, 44, 0)'} : {},
        false)
    );

    this.config.deleteEntityTitle = event => this.translate.instant('scheduler.delete-scheduler-event-title', {schedulerEventName: event.name});
    this.config.deleteEntityContent = () => this.translate.instant('scheduler.delete-scheduler-event-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('scheduler.delete-scheduler-events-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('scheduler.delete-scheduler-events-text');

    this.config.entitiesFetchFunction = pageLink => this.schedulerEventService.getSchedulerEvents(pageLink);
    this.config.deleteEntity = id => this.schedulerEventService.deleteSchedulerEvent(id.id);

    this.config.cellActionDescriptors = this.configureCellActions();

    this.config.addEntity = () => this.openSchedulerEventDialog(null, true);
    this.config.handleRowClick = ($event, event) => {
      if ($event) {
        $event.stopPropagation();
      }
      this.openEdit(event);
      return true;
    };
  }

  resolve(route: ActivatedRouteSnapshot): EntityTableConfig<SchedulerEventWithCustomerInfo> {
    this.config.componentsData = {};
    this.store.pipe(select(selectAuthUser), take(1)).subscribe(authUser => {
      this.config.componentsData.isCustomerUser = authUser.authority === Authority.CUSTOMER_USER;
    });
    return this.config;
  }

  private configureCellActions(): Array<CellActionDescriptor<SchedulerEventWithCustomerInfo>> {
    return [
      {
        name: this.translate.instant('scheduler.disable'),
        icon: 'toggle_on',
        isEnabled: (entity) => entity.enabled,
        onAction: ($event, entity) => this.toggleEnabled($event, entity, false)
      },
      {
        name: this.translate.instant('scheduler.enable'),
        icon: 'toggle_off',
        isEnabled: (entity) => !entity.enabled,
        onAction: ($event, entity) => this.toggleEnabled($event, entity, true)
      },
      {
        name: this.translate.instant('scheduler.edit-scheduler-event'),
        icon: 'edit',
        isEnabled: () => true,
        onAction: ($event, entity) => {
          if ($event) {
            $event.stopPropagation();
          }
          this.openEdit(entity);
        }
      }
    ];
  }

  private typeLabel(type: string): string {
    // hasOwnProperty rather than a bare lookup: the REST API accepts any string as an event type
    // (custom types are a supported feature), so `__proto__` would resolve to Object.prototype and
    // hand translate.instant() an undefined key, which throws and takes the whole row's render with it.
    const configType = Object.prototype.hasOwnProperty.call(schedulerEventConfigTypes, type)
      ? schedulerEventConfigTypes[type] : null;
    return configType ? this.translate.instant(configType.name) : type;
  }

  private statusOf(entity: SchedulerEventWithCustomerInfo): SchedulerEventStatus {
    return schedulerEventStatus(entity.schedule, entity.enabled);
  }

  private toggleEnabled($event: Event, entity: SchedulerEventWithCustomerInfo, enabled: boolean) {
    if ($event) {
      $event.stopPropagation();
    }
    this.schedulerEventService.setSchedulerEventEnabled(entity.id.id, enabled)
      .subscribe({
        next: () => this.config.updateData(),
        error: err => console.error('[Scheduler] enable/disable failed', err)
      });
  }

  private openEdit(eventInfo: SchedulerEventWithCustomerInfo) {
    this.schedulerEventService.getSchedulerEvent(eventInfo.id.id).subscribe({
      next: fullEvent => {
        this.openSchedulerEventDialog(fullEvent, false).subscribe(res => {
          if (res) {
            this.config.updateData();
          }
        });
      },
      error: err => console.error('[Scheduler] load event failed', err)
    });
  }

  private openSchedulerEventDialog(schedulerEvent: SchedulerEvent | null, isAdd: boolean): Observable<SchedulerEvent> {
    return this.dialog.open<SchedulerEventDialogComponent, SchedulerEventDialogData, SchedulerEvent>(
      SchedulerEventDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          schedulerEvent,
          isAdd,
          isCustomerUser: this.config.componentsData?.isCustomerUser === true
        }
      }).afterClosed();
  }
}
