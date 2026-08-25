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
import { ReportInfo } from '@shared/models/report.models';
import { ReportService } from '@core/http/report.service';
import { UserService } from '@core/http/user.service';
import { PageData } from '@shared/models/page/page-data';

// Table-config resolver for the generated-reports list (/api/v2/reportInfos). Reports are produced
// by the scheduler / the templates list's "Generate report" action, never created here, so
// addEnabled stays false.
//
// ReportInfo carries only the raw `templateId` UUID and `userId` - no names (Report.java) - but the
// columns that matter to an operator are the template and the user, not their UUIDs. Rather than
// widen the DAO projection with two more joins, the names are resolved page-by-page here and
// memoized: one extra request per facet per page at worst, none at all once the caches are warm.
@Injectable()
export class ReportHistoryTableConfigResolver {

  private readonly config: EntityTableConfig<ReportInfo> = new EntityTableConfig<ReportInfo>();

  private readonly templateNames = new Map<string, string>();
  private readonly userNames = new Map<string, string>();

  constructor(private reportService: ReportService,
              private userService: UserService,
              private translate: TranslateService,
              private datePipe: DatePipe) {

    this.config.entityType = EntityType.REPORT;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.REPORT);
    this.config.entityResources = entityTypeResources.get(EntityType.REPORT);
    this.config.tableTitle = this.translate.instant('report.reports');
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = true;
    this.config.addEnabled = false;
    this.config.entitiesDeleteEnabled = true;
    this.config.entityTitle = (report) => report ? report.name : '';

    this.config.columns.push(
      new DateEntityTableColumn<ReportInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<ReportInfo>('name', 'report.file-name', '25%'),
      new EntityTableColumn<ReportInfo>('templateId', 'report.report-template', '25%',
        entity => this.templateNames.get(entity.templateId) ?? '', () => ({}), false),
      new EntityTableColumn<ReportInfo>('userId', 'report.user', '20%',
        entity => this.userNames.get(entity.userId?.id) ?? '', () => ({}), false),
      new EntityTableColumn<ReportInfo>('format', 'report.format', '10%')
    );

    this.config.deleteEntityTitle = report => this.translate.instant('report.delete-report-title', {reportName: report.name});
    this.config.deleteEntityContent = () => this.translate.instant('report.delete-report-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('report.delete-reports-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('report.delete-reports-text');

    this.config.entitiesFetchFunction = pageLink =>
      this.reportService.getReportInfos(pageLink, {}).pipe(
        mergeMap(pageData => this.resolveNames(pageData))
      );
    this.config.deleteEntity = id => this.reportService.deleteReport(id.id);

    this.config.cellActionDescriptors = this.configureCellActions();
  }

  resolve(route: ActivatedRouteSnapshot): EntityTableConfig<ReportInfo> {
    this.config.componentsData = {};
    // Dropped on every (re)entry rather than kept for the resolver's lifetime: this is a singleton,
    // so a name cached on an earlier visit would keep showing a since-renamed template or user.
    this.templateNames.clear();
    this.userNames.clear();
    return this.config;
  }

  // Fills the two name caches for whatever this page references and then passes the page through
  // untouched - the columns read the caches synchronously, so they must be warm before the rows
  // render. A failed lookup is not fatal: the cell falls back to blank rather than breaking the list.
  private resolveNames(pageData: PageData<ReportInfo>): Observable<PageData<ReportInfo>> {
    const templateIds = unique(pageData.data.map(r => r.templateId).filter(id => !!id && !this.templateNames.has(id)));
    const userIds = unique(pageData.data.map(r => r.userId?.id).filter(id => !!id && !this.userNames.has(id)));
    const lookups: Observable<any>[] = [];
    if (templateIds.length) {
      lookups.push(this.reportService.getReportTemplates(templateIds, {ignoreErrors: true}).pipe(
        map(templates => templates.forEach(t => this.templateNames.set(t.id.id, t.name))),
        // catchError, not just ignoreErrors: the latter only suppresses the global error toast, the
        // observable still errors - and a single deleted template would then take the whole list
        // down instead of leaving one cell blank.
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

  private configureCellActions(): Array<CellActionDescriptor<ReportInfo>> {
    return [
      {
        name: this.translate.instant('report.download'),
        icon: 'file_download',
        isEnabled: () => true,
        onAction: ($event, entity) => this.downloadReport($event, entity)
      }
    ];
  }

  private downloadReport($event: Event, entity: ReportInfo) {
    if ($event) {
      $event.stopPropagation();
    }
    this.reportService.downloadReport(entity.id.id).subscribe({
      error: err => console.error('[Reporting] report download failed', err)
    });
  }
}

function unique(ids: string[]): string[] {
  return Array.from(new Set(ids));
}

function userDisplayName(user: {firstName?: string; lastName?: string; email?: string}): string {
  const name = [user.firstName, user.lastName].filter(part => !!part).join(' ').trim();
  return name || user.email || '';
}
