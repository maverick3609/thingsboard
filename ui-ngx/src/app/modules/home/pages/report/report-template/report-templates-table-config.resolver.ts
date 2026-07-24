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
import { ActivatedRouteSnapshot, Router } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { of } from 'rxjs';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { ReportTemplateInfo } from '@shared/models/report.models';
import { ReportService } from '@core/http/report.service';

// Table-config resolver for the report templates list (/api/reportTemplateInfos/all). Mirrors
// SchedulerEventsTableConfigResolver's add/edit-dialog CRUD structure: unlike the report-history
// list (read-only), templates ARE created/edited here, so addEnabled stays the EntityTableConfig
// default (true). Add/edit navigate to the full-page designer (report-routing.module.ts's
// 'templates/new' and 'templates/:reportTemplateId') rather than opening a MatDialog - see C1 Task 3.
@Injectable()
export class ReportTemplatesTableConfigResolver {

  private readonly config: EntityTableConfig<ReportTemplateInfo> = new EntityTableConfig<ReportTemplateInfo>();

  constructor(private reportService: ReportService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private router: Router) {

    this.config.entityType = EntityType.REPORT_TEMPLATE;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.REPORT_TEMPLATE);
    this.config.entityResources = entityTypeResources.get(EntityType.REPORT_TEMPLATE);
    this.config.tableTitle = this.translate.instant('report.report-templates');
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = true;
    this.config.entitiesDeleteEnabled = true;
    this.config.entityTitle = (template) => template ? template.name : '';

    this.config.columns.push(
      new DateEntityTableColumn<ReportTemplateInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<ReportTemplateInfo>('name', 'report.name', '25%'),
      new EntityTableColumn<ReportTemplateInfo>('type', 'report.template-type', '15%'),
      new EntityTableColumn<ReportTemplateInfo>('format', 'report.format', '15%'),
      new EntityTableColumn<ReportTemplateInfo>('customerTitle', 'customer.customer', '20%')
    );

    this.config.deleteEntityTitle = template =>
      this.translate.instant('report.delete-report-template-title', {reportTemplateName: template.name});
    this.config.deleteEntityContent = () => this.translate.instant('report.delete-report-template-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('report.delete-report-templates-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('report.delete-report-templates-text');

    this.config.entitiesFetchFunction = pageLink => this.reportService.getReportTemplateInfos(pageLink, {});
    this.config.deleteEntity = id => this.reportService.deleteReportTemplate(id.id);

    // Navigate rather than return a created/edited entity: the designer is a full-page route, not a
    // dialog, so there is nothing to emit back to entities-table.component.ts#addEntity - it only acts
    // (updateData/entityAdded) on a truthy emission, hence of(null) here is a deliberate no-op.
    this.config.addEntity = () => {
      this.router.navigate(['/features/reports/templates/new']);
      return of(null);
    };
    this.config.handleRowClick = ($event, entity) => {
      if ($event) {
        $event.stopPropagation();
      }
      this.router.navigate(['/features/reports/templates', entity.id.id]);
      return true;
    };
  }

  resolve(route: ActivatedRouteSnapshot): EntityTableConfig<ReportTemplateInfo> {
    this.config.componentsData = {};
    return this.config;
  }
}
