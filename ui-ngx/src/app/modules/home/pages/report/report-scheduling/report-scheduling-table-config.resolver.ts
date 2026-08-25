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
import { forkJoin, Observable, of } from 'rxjs';
import { catchError, map, mergeMap } from 'rxjs/operators';
import {
  CellActionDescriptor,
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import {
  SchedulerEvent,
  SchedulerEventWithCustomerInfo,
  schedulerEventScheduleText
} from '@shared/models/scheduler-event.models';
import { SchedulerEventService } from '@core/http/scheduler-event.service';
import { ReportService } from '@core/http/report.service';
import { UserService } from '@core/http/user.service';
import { PageData } from '@shared/models/page/page-data';
import {
  SchedulerEventDialogComponent,
  SchedulerEventDialogData
} from '@home/pages/scheduler/scheduler-event-dialog.component';

// The Reporting section's "Scheduling" tab: the scheduler-event list narrowed to the one event type
// that produces reports. There is no separate scheduled-report entity on the backend - a scheduled
// report IS a SchedulerEvent of type 'generateReport' whose configuration is a bare ReportConfig
// ({reportTemplateId, userId, timezone, ...}) - so this reuses SchedulerEventService's existing
// type filter rather than adding an endpoint.
//
// Reaching this list through the generic Scheduler page is still possible and still works; this tab
// exists because "which reports are scheduled, for whom, and when" is the question an operator
// actually asks, and the generic list answers it with an event type and a customer title instead.
export const GENERATE_REPORT_EVENT_TYPE = 'generateReport';

@Injectable()
export class ReportSchedulingTableConfigResolver {

  private readonly config: EntityTableConfig<SchedulerEventWithCustomerInfo> =
    new EntityTableConfig<SchedulerEventWithCustomerInfo>();

  // The list projection carries `configuration` only for events fetched individually, so the
  // template/user each row points at has to be resolved separately - same memoized page-by-page
  // lookup the Reports tab uses (report-history-table-config.resolver.ts).
  private readonly templateNames = new Map<string, string>();
  private readonly userNames = new Map<string, string>();
  private readonly eventConfigs = new Map<string, {templateId?: string; userId?: string}>();

  constructor(private schedulerEventService: SchedulerEventService,
              private reportService: ReportService,
              private userService: UserService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog) {

    this.config.entityType = EntityType.SCHEDULER_EVENT;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.SCHEDULER_EVENT);
    this.config.entityResources = entityTypeResources.get(EntityType.SCHEDULER_EVENT);
    this.config.tableTitle = this.translate.instant('report.scheduling');
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = true;
    this.config.entitiesDeleteEnabled = true;
    this.config.entityTitle = (event) => event ? event.name : '';

    this.config.columns.push(
      new DateEntityTableColumn<SchedulerEventWithCustomerInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('name', 'report.name', '20%'),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('reportTemplate', 'report.report-template', '20%',
        entity => this.templateNames.get(this.eventConfigs.get(entity.id.id)?.templateId) ?? '', () => ({}), false),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('user', 'report.user', '20%',
        entity => this.userNames.get(this.eventConfigs.get(entity.id.id)?.userId) ?? '', () => ({}), false),
      new EntityTableColumn<SchedulerEventWithCustomerInfo>('schedule', 'report.schedule', '25%',
        entity => schedulerEventScheduleText(entity.schedule, this.translate), () => ({}), false)
    );

    this.config.deleteEntityTitle = event =>
      this.translate.instant('scheduler.delete-scheduler-event-title', {schedulerEventName: event.name});
    this.config.deleteEntityContent = () => this.translate.instant('scheduler.delete-scheduler-event-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('scheduler.delete-scheduler-events-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('scheduler.delete-scheduler-events-text');

    this.config.entitiesFetchFunction = pageLink =>
      this.schedulerEventService.getSchedulerEvents(pageLink, GENERATE_REPORT_EVENT_TYPE).pipe(
        mergeMap(pageData => this.resolveNames(pageData))
      );
    this.config.deleteEntity = id => this.schedulerEventService.deleteSchedulerEvent(id.id);

    this.config.cellActionDescriptors = this.configureCellActions();

    // A single addActionDescriptor rather than addEntity: it lets the toolbar button say
    // "Schedule report" instead of the generic entity-translations "Add scheduler event".
    this.config.addActionDescriptors = [
      {
        name: this.translate.instant('report.schedule-report'),
        icon: 'add',
        isEnabled: () => true,
        onAction: () => this.openDialog(null).subscribe(res => {
          if (res) {
            this.config.updateData();
          }
        })
      }
    ];
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
    // Dropped on every (re)entry rather than kept for the resolver's lifetime: this is a singleton,
    // so anything cached on an earlier visit would keep showing since-renamed templates/users or a
    // since-repointed event configuration.
    this.templateNames.clear();
    this.userNames.clear();
    this.eventConfigs.clear();
    return this.config;
  }

  // Loads each row's full event (the list projection has no `configuration`) and then the names its
  // ReportConfig points at. Every lookup is memoized and ignoreErrors'd - a deleted template or a
  // deactivated user leaves that one cell blank rather than failing the whole list.
  private resolveNames(pageData: PageData<SchedulerEventWithCustomerInfo>)
      : Observable<PageData<SchedulerEventWithCustomerInfo>> {
    const missing = pageData.data.filter(event => !this.eventConfigs.has(event.id.id));
    if (!missing.length) {
      return this.resolveReferencedNames(pageData);
    }
    return forkJoin(missing.map(event =>
      this.schedulerEventService.getSchedulerEvent(event.id.id, {ignoreErrors: true}).pipe(
        map(full => this.eventConfigs.set(event.id.id, {
          templateId: (full.configuration as any)?.reportTemplateId?.id,
          userId: (full.configuration as any)?.userId?.id
        })),
        // catchError, not just ignoreErrors: the latter only suppresses the global error toast, the
        // observable still errors - and one unreadable event would then take the whole list down.
        catchError(() => of(null))
      ))).pipe(mergeMap(() => this.resolveReferencedNames(pageData)));
  }

  private resolveReferencedNames(pageData: PageData<SchedulerEventWithCustomerInfo>)
      : Observable<PageData<SchedulerEventWithCustomerInfo>> {
    const configs = pageData.data.map(event => this.eventConfigs.get(event.id.id)).filter(c => !!c);
    const templateIds = unique(configs.map(c => c.templateId).filter(id => !!id && !this.templateNames.has(id)));
    const userIds = unique(configs.map(c => c.userId).filter(id => !!id && !this.userNames.has(id)));
    const lookups: Observable<any>[] = [];
    if (templateIds.length) {
      lookups.push(this.reportService.getReportTemplates(templateIds, {ignoreErrors: true}).pipe(
        map(templates => templates.forEach(t => this.templateNames.set(t.id.id, t.name))),
        catchError(() => of(null))
      ));
    }
    if (userIds.length) {
      lookups.push(this.userService.getUsersByIds(userIds, {ignoreErrors: true}).pipe(
        map(users => users.forEach(u => this.userNames.set(u.id.id, userDisplayName(u)))),
        catchError(() => of(null))
      ));
    }
    return lookups.length ? forkJoin(lookups).pipe(map(() => pageData)) : of(pageData);
  }

  private configureCellActions(): Array<CellActionDescriptor<SchedulerEventWithCustomerInfo>> {
    return [
      {
        name: this.translate.instant('scheduler.enable'),
        icon: 'play_arrow',
        isEnabled: (entity) => !entity.enabled,
        onAction: ($event, entity) => this.toggleEnabled($event, entity, true)
      },
      {
        name: this.translate.instant('scheduler.disable'),
        icon: 'pause',
        isEnabled: (entity) => entity.enabled,
        onAction: ($event, entity) => this.toggleEnabled($event, entity, false)
      }
    ];
  }

  private toggleEnabled($event: Event, entity: SchedulerEventWithCustomerInfo, enabled: boolean): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.schedulerEventService.setSchedulerEventEnabled(entity.id.id, enabled).subscribe({
      next: () => this.config.updateData(),
      error: err => console.error('[Reporting] enable/disable scheduled report failed', err)
    });
  }

  private openEdit(eventInfo: SchedulerEventWithCustomerInfo): void {
    this.schedulerEventService.getSchedulerEvent(eventInfo.id.id).subscribe({
      next: fullEvent => this.openDialog(fullEvent).subscribe(res => {
        if (res) {
          // The configuration may now point at a different template/user - drop the memoized copy so
          // the next fetch re-reads it.
          this.eventConfigs.delete(eventInfo.id.id);
          this.config.updateData();
        }
      }),
      error: err => console.error('[Reporting] load scheduled report failed', err)
    });
  }

  private openDialog(schedulerEvent: SchedulerEvent | null): Observable<SchedulerEvent> {
    return this.dialog.open<SchedulerEventDialogComponent, SchedulerEventDialogData, SchedulerEvent>(
      SchedulerEventDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          schedulerEvent,
          isAdd: !schedulerEvent,
          isCustomerUser: false,
          lockedType: GENERATE_REPORT_EVENT_TYPE
        }
      }).afterClosed();
  }
}

function unique(ids: string[]): string[] {
  return Array.from(new Set(ids));
}

function userDisplayName(user: {firstName?: string; lastName?: string; email?: string}): string {
  const name = [user.firstName, user.lastName].filter(part => !!part).join(' ').trim();
  return name || user.email || '';
}
