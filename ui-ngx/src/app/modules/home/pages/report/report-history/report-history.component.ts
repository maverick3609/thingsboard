// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { ReportInfo } from '@shared/models/report.models';

// Host component for the report history list. Just hosts tb-entities-table, mirroring the
// SchedulerEventsComponent list-mode half (no calendar mode - reports have no schedule of
// their own to visualize).
@Component({
  selector: 'tb-report-history',
  templateUrl: './report-history.component.html',
  styleUrls: ['./report-history.component.scss'],
  standalone: false
})
export class ReportHistoryComponent implements OnInit {

  entitiesTableConfig: EntityTableConfig<ReportInfo>;

  constructor(private route: ActivatedRoute) {
  }

  ngOnInit(): void {
    this.entitiesTableConfig = this.route.snapshot.data.entitiesTableConfig;
  }
}
