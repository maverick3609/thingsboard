// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { ReportTemplateInfo } from '@shared/models/report.models';

// Host component for the report templates list. Just hosts tb-entities-table, mirroring
// ReportHistoryComponent (Task 23).
@Component({
  selector: 'tb-report-templates',
  templateUrl: './report-templates.component.html',
  standalone: false
})
export class ReportTemplatesComponent implements OnInit {

  entitiesTableConfig: EntityTableConfig<ReportTemplateInfo>;

  constructor(private route: ActivatedRoute) {
  }

  ngOnInit(): void {
    this.entitiesTableConfig = this.route.snapshot.data.entitiesTableConfig;
  }
}
