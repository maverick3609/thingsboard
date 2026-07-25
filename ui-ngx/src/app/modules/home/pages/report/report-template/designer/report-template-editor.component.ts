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

import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ReportService } from '@core/http/report.service';
import { ReportTemplate, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { ReportComponent, ReportTemplateConfigModel, newPdfReportTemplateConfig } from '@shared/models/report-configuration.models';
import { deserialize, serialize } from '@home/pages/report/report-template/designer/report-configuration.serializer';
import { ReportPageSettings } from '@home/pages/report/report-template/designer/report-page-settings.component';

// PDF-only projection of config's page-level fields for tb-report-page-settings (T5); null for a
// CSV config, which has no page settings - the shell hides the panel rather than show one whose
// edits would have nowhere to go.
function toPageSettings(config: ReportTemplateConfigModel): ReportPageSettings | null {
  return config?.format === 'PDF' ? {
    pageSize: config.pageSize,
    pageOrientation: config.pageOrientation,
    pageMargins: config.pageMargins,
    pageBackground: config.pageBackground,
    namePattern: config.namePattern,
    timeDataPattern: config.timeDataPattern
  } : null;
}

// Full-page three-pane designer shell (design spec R2c "C1"): a toolbar (back/name/save/edit-preview
// toggle) over a left/center/right body. T3 wired load/save + the placeholder panes; T5 fills the
// right pane's page-settings branch (selected === null). The palette (left)/canvas (center) and
// component-config (right, selected !== null) panes are filled in by T6/T8-T11, and the preview
// overlay behind the toggle by T7 - they all bind against `config`/`selected`.
// Reachable at both '/features/reports/templates/new' and '/features/reports/templates/:reportTemplateId'
// (report-routing.module.ts); the 'new' route has no reportTemplateId param, so a missing param is
// treated the same as the literal 'new' segment.
@Component({
  selector: 'tb-report-template-editor',
  templateUrl: './report-template-editor.component.html',
  styleUrls: ['./report-template-editor.component.scss'],
  standalone: false
})
export class ReportTemplateEditorComponent implements OnInit {

  isAdd: boolean;
  reportTemplate: ReportTemplate;
  config: ReportTemplateConfigModel;
  selected: ReportComponent | null = null;
  viewMode: 'edit' | 'preview' = 'edit';
  pageSettings: ReportPageSettings | null = null;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private reportService: ReportService) {
  }

  ngOnInit(): void {
    const reportTemplateId = this.route.snapshot.paramMap.get('reportTemplateId');
    this.isAdd = !reportTemplateId || reportTemplateId === 'new';
    if (this.isAdd) {
      this.reportTemplate = {
        name: '',
        format: TbReportFormat.PDF,
        type: ReportTemplateType.REPORT
      } as ReportTemplate;
      this.config = newPdfReportTemplateConfig();
      this.pageSettings = toPageSettings(this.config);
    } else {
      this.reportService.getReportTemplateById(reportTemplateId).subscribe(reportTemplate => {
        this.reportTemplate = reportTemplate;
        this.config = deserialize(reportTemplate.configuration);
        this.pageSettings = toPageSettings(this.config);
      });
    }
  }

  onPageSettingsChange(value: ReportPageSettings): void {
    if (this.config?.format === 'PDF') {
      Object.assign(this.config, value);
    }
    this.pageSettings = value;
  }

  save(): void {
    const reportTemplate: ReportTemplate = {
      ...this.reportTemplate,
      configuration: serialize(this.config)
    };
    this.reportService.saveReportTemplate(reportTemplate).subscribe(() => {
      this.goBack();
    });
  }

  goBack(): void {
    this.router.navigate(['../'], {relativeTo: this.route});
  }
}
