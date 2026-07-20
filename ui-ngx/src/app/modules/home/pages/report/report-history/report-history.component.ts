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
