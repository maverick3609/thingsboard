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
import { Observable } from 'rxjs';
import {
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { ReportTemplate, ReportTemplateInfo } from '@shared/models/report.models';
import { ReportService } from '@core/http/report.service';
import {
  ReportTemplateDialogComponent,
  ReportTemplateDialogData
} from '@home/pages/report/report-template/report-template-dialog.component';

// Table-config resolver for the report templates list (/api/reportTemplateInfos/all). Mirrors
// SchedulerEventsTableConfigResolver's add/edit-dialog CRUD structure: unlike the report-history
// list (read-only), templates ARE created/edited here, so addEnabled stays the EntityTableConfig
// default (true).
@Injectable()
export class ReportTemplatesTableConfigResolver {

  private readonly config: EntityTableConfig<ReportTemplateInfo> = new EntityTableConfig<ReportTemplateInfo>();

  constructor(private reportService: ReportService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog) {

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

    this.config.addEntity = () => this.openReportTemplateDialog(null, true);
    this.config.handleRowClick = ($event, entity) => {
      if ($event) {
        $event.stopPropagation();
      }
      this.openEdit(entity);
      return true;
    };
  }

  resolve(route: ActivatedRouteSnapshot): EntityTableConfig<ReportTemplateInfo> {
    this.config.componentsData = {};
    return this.config;
  }

  // ReportTemplateInfo (the list projection) has no `configuration` field, so editing requires a
  // fetch of the full ReportTemplate first.
  private openEdit(templateInfo: ReportTemplateInfo) {
    this.reportService.getReportTemplateById(templateInfo.id.id).subscribe({
      next: fullTemplate => {
        this.openReportTemplateDialog(fullTemplate, false).subscribe(res => {
          if (res) {
            this.config.updateData();
          }
        });
      },
      error: err => console.error('[Reporting] load report template failed', err)
    });
  }

  private openReportTemplateDialog(reportTemplate: ReportTemplate | null, isAdd: boolean): Observable<ReportTemplate> {
    return this.dialog.open<ReportTemplateDialogComponent, ReportTemplateDialogData, ReportTemplate>(
      ReportTemplateDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {
          reportTemplate,
          isAdd
        }
      }).afterClosed();
  }
}
