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
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { DatePipe } from '@angular/common';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import {
  CellActionDescriptor,
  DateEntityTableColumn,
  EntityTableColumn,
  EntityTableConfig
} from '@home/models/entity/entities-table-config.models';
import { EntityType, entityTypeResources, entityTypeTranslations } from '@shared/models/entity-type.models';
import { ReportTemplateInfo, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { ReportService } from '@core/http/report.service';
import { deserialize } from '@home/pages/report/report-template/designer/report-configuration.serializer';
import { ReportImportExportService } from '@home/pages/report/report-template/designer/report-import-export';
import {
  ReportTemplateDialogComponent,
  ReportTemplateDialogData
} from '@home/pages/report/report-template/report-template-dialog.component';
import {
  ReportTemplatesTableHeaderComponent
} from '@home/pages/report/report-template/report-templates-table-header.component';

// Filter state owned by the table header (report-templates-table-header.component.ts) and read back
// by entitiesFetchFunction - the backend already supports both facets as query params
// (ReportTemplateController#getAllReportTemplateInfos), so this is pure plumbing, no client-side
// filtering.
export interface ReportTemplatesFilterConfig {
  typeList?: Array<ReportTemplateType>;
  formatList?: Array<TbReportFormat>;
}

export class ReportTemplatesTableConfig extends EntityTableConfig<ReportTemplateInfo> {
  templatesFilterConfig: ReportTemplatesFilterConfig = {};
}

// Table-config resolver for the report templates list (/api/reportTemplateInfos/all). Mirrors
// SchedulerEventsTableConfigResolver's add/edit-dialog CRUD structure: unlike the report history
// list (read-only), templates ARE created/edited here, so addEnabled stays the EntityTableConfig
// default (true).
//
// The add button is a two-item menu (addActionDescriptors) rather than a single button: "create"
// opens the root-fields dialog and "import" reads an exported JSON file, and BOTH end up at the
// same place - the full-page designer's 'new' route with a router-state seed, since neither path
// persists anything until the user saves in the designer.
@Injectable()
export class ReportTemplatesTableConfigResolver {

  private readonly config = new ReportTemplatesTableConfig();

  constructor(private reportService: ReportService,
              private reportImportExport: ReportImportExportService,
              private translate: TranslateService,
              private datePipe: DatePipe,
              private dialog: MatDialog,
              private store: Store<AppState>,
              private router: Router) {

    this.config.entityType = EntityType.REPORT_TEMPLATE;
    this.config.entityTranslations = entityTypeTranslations.get(EntityType.REPORT_TEMPLATE);
    this.config.entityResources = entityTypeResources.get(EntityType.REPORT_TEMPLATE);
    this.config.tableTitle = this.translate.instant('report.report-templates');
    this.config.detailsPanelEnabled = false;
    this.config.selectionEnabled = true;
    this.config.entitiesDeleteEnabled = true;
    this.config.entityTitle = (template) => template ? template.name : '';
    this.config.headerComponent = ReportTemplatesTableHeaderComponent;

    this.config.columns.push(
      new DateEntityTableColumn<ReportTemplateInfo>('createdTime', 'common.created-time', this.datePipe, '150px'),
      new EntityTableColumn<ReportTemplateInfo>('name', 'report.name', '25%'),
      new EntityTableColumn<ReportTemplateInfo>('type', 'report.template-type', '15%',
        entity => this.templateTypeLabel(entity.type)),
      new EntityTableColumn<ReportTemplateInfo>('format', 'report.format', '15%'),
      new EntityTableColumn<ReportTemplateInfo>('customerTitle', 'customer.customer', '20%')
    );

    this.config.deleteEntityTitle = template =>
      this.translate.instant('report.delete-report-template-title', {reportTemplateName: template.name});
    this.config.deleteEntityContent = () => this.translate.instant('report.delete-report-template-text');
    this.config.deleteEntitiesTitle = count => this.translate.instant('report.delete-report-templates-title', {count});
    this.config.deleteEntitiesContent = () => this.translate.instant('report.delete-report-templates-text');

    this.config.entitiesFetchFunction = pageLink =>
      this.reportService.getReportTemplateInfos(pageLink, this.config.templatesFilterConfig);
    this.config.deleteEntity = id => this.reportService.deleteReportTemplate(id.id);

    this.config.cellActionDescriptors = this.configureCellActions();
    this.config.addActionDescriptors = [
      {
        name: this.translate.instant('report.create-report-template'),
        icon: 'insert_drive_file',
        isEnabled: () => true,
        onAction: () => this.openCreateDialog()
      },
      {
        name: this.translate.instant('report.import-report-template'),
        icon: 'file_upload',
        isEnabled: () => true,
        onAction: () => this.pickImportFile()
      }
    ];

    this.config.handleRowClick = ($event, entity) => {
      if ($event) {
        $event.stopPropagation();
      }
      this.openDesigner(entity.id.id);
      return true;
    };
  }

  resolve(route: ActivatedRouteSnapshot): ReportTemplatesTableConfig {
    this.config.componentsData = {};
    this.config.templatesFilterConfig = {};
    return this.config;
  }

  private templateTypeLabel(type: ReportTemplateType): string {
    return this.translate.instant(type === ReportTemplateType.SUB_REPORT ? 'report.type-sub-report' : 'report.type-report');
  }

  private configureCellActions(): Array<CellActionDescriptor<ReportTemplateInfo>> {
    return [
      {
        // Asks the server to render this template right now. The result is asynchronous - it shows
        // up in the Reports tab once the render job finishes, NOT as an inline download.
        name: this.translate.instant('report.generate-report'),
        icon: 'play_circle_outline',
        isEnabled: (entity) => entity.type !== ReportTemplateType.SUB_REPORT,
        onAction: ($event, entity) => this.generateReport($event, entity)
      },
      {
        name: this.translate.instant('report.export-report-template'),
        icon: 'file_download',
        isEnabled: () => true,
        onAction: ($event, entity) => this.exportTemplate($event, entity)
      },
      {
        name: this.translate.instant('report.edit-report-template'),
        icon: 'edit',
        isEnabled: () => true,
        onAction: ($event, entity) => this.openEditDialog($event, entity)
      }
    ];
  }

  private generateReport($event: Event, entity: ReportTemplateInfo): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.reportService.requestReport(entity.id.id).subscribe(() =>
      this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('report.generating-report'),
        type: 'success',
        duration: 3000
      })));
  }

  // The list projection carries no `configuration`, so the full template has to be loaded before it
  // can be exported. Deserialize-then-export (rather than dumping the raw stored JSON) so a
  // list-exported file is byte-identical to one exported from inside the designer.
  private exportTemplate($event: Event, entity: ReportTemplateInfo): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.reportService.getReportTemplateById(entity.id.id).subscribe(template =>
      this.reportImportExport.exportTemplate(template, deserialize(template.configuration)));
  }

  private openCreateDialog(): void {
    this.dialog.open<ReportTemplateDialogComponent, ReportTemplateDialogData, any>(
      ReportTemplateDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: {isAdd: true}
      }).afterClosed().subscribe(seed => {
      if (seed) {
        this.openDesigner('new', seed);
      }
    });
  }

  // Edits only the root fields (name/description; format/type are locked by the dialog) and saves
  // them straight back - the configuration tree is edited in the designer, reached by row click.
  private openEditDialog($event: Event, entity: ReportTemplateInfo): void {
    if ($event) {
      $event.stopPropagation();
    }
    this.reportService.getReportTemplateById(entity.id.id).subscribe(template => {
      this.dialog.open<ReportTemplateDialogComponent, ReportTemplateDialogData, any>(
        ReportTemplateDialogComponent, {
          disableClose: true,
          panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
          data: {reportTemplate: template, isAdd: false}
        }).afterClosed().subscribe(edited => {
        if (edited) {
          this.reportService.saveReportTemplate({...template, ...edited})
            .subscribe(() => this.config.updateData());
        }
      });
    });
  }

  // A bare file input rather than the platform ImportExportService: that service transitively
  // reaches DialogService, which has a pre-existing circular import (see report-import-export.ts's
  // header for the full diagnosis) - this feature deliberately keeps clear of that chain.
  private pickImportFile(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json';
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) {
        return;
      }
      this.reportImportExport.importTemplate(file).subscribe({
        next: imported => this.openDesigner('new', imported),
        error: err => {
          console.error('[Reporting] report template import failed', err);
          this.store.dispatch(new ActionNotificationShow({
            message: this.translate.instant('report.designer.import-error'),
            type: 'error'
          }));
        }
      });
    };
    input.click();
  }

  // The seed rides in history.state under one namespaced key (the designer reads and clears exactly
  // that key - see ReportTemplateEditorComponent#takeSeed).
  private openDesigner(segment: string, seed?: any): void {
    this.router.navigate(['/reporting/templates', segment],
      seed ? {state: {reportTemplateSeed: seed}} : undefined);
  }
}
