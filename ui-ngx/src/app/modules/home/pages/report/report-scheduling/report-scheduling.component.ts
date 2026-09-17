// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { SchedulerEventWithCustomerInfo } from '@shared/models/scheduler-event.models';

// Host component for the Reporting section's Scheduling tab - hosts tb-entities-table over the
// 'generateReport' scheduler events, mirroring ReportHistoryComponent.
@Component({
  selector: 'tb-report-scheduling',
  templateUrl: './report-scheduling.component.html',
  styleUrls: ['./report-scheduling.component.scss'],
  standalone: false
})
export class ReportSchedulingComponent implements OnInit {

  entitiesTableConfig: EntityTableConfig<SchedulerEventWithCustomerInfo>;

  constructor(private route: ActivatedRoute) {
  }

  ngOnInit(): void {
    this.entitiesTableConfig = this.route.snapshot.data.entitiesTableConfig;
  }
}
