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

// Template-level "manage filters" list (Task 10, deliverable 2) - the config.filters[] counterpart
// of report-entity-aliases.component.ts; see that file's header for the shared design rationale
// (local throwaway aliasController, no in-use-on-delete guard). The one filter-specific concern is
// the keyFilters shape boundary: config.filters[].keyFilters is report KeyFilter[] ({predicate}), but
// FilterDialogComponent (and the platform Filters map placed into FilterDialogData.filters for its
// duplicate-name check) both operate on KeyFilterInfo[] ({predicates[]}) - going through
// aliasController.getFilters()/updateFilters() (Task 9's shim) for every read/write, exactly as
// report-alias-callbacks.ts's createFilter/editFilter do internally, means this component never
// touches keyFiltersToKeyFilterInfos/keyFilterInfosToKeyFilters directly and can't get the shape
// wrong.
import { Component, Input, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Filter } from '@shared/models/query/query.models';
import { IAliasController } from '@core/api/widget-api.models';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { createReportAliasController } from '@home/pages/report/report-template/designer/data/report-alias-controller';
import {
  createReportAliasCallbacks,
  ReportAliasCallbacks
} from '@home/pages/report/report-template/designer/data/report-alias-callbacks';

@Component({
    selector: 'tb-report-filters',
    templateUrl: './report-filters.component.html',
    styleUrls: [],
    standalone: false
})
export class ReportFiltersComponent implements OnInit {

  @Input()
  config: ReportTemplateConfigModel;

  filters: Filter[] = [];

  private aliasController: IAliasController;
  private callbacks: ReportAliasCallbacks;

  constructor(private dialog: MatDialog) {
  }

  ngOnInit(): void {
    this.aliasController = createReportAliasController(() => this.config);
    this.callbacks = createReportAliasCallbacks(this.dialog, this.aliasController);
    this.refresh();
  }

  addFilter(): void {
    this.callbacks.createFilter('').subscribe((filter) => {
      if (filter) {
        this.refresh();
      }
    });
  }

  editFilter(filter: Filter): void {
    this.callbacks.editFilter(filter).subscribe((result) => {
      if (result) {
        this.refresh();
      }
    });
  }

  deleteFilter(filter: Filter): void {
    const filters = this.aliasController.getFilters();
    delete filters[filter.id];
    this.aliasController.updateFilters(filters);
    this.refresh();
  }

  trackById(_index: number, filter: Filter): string {
    return filter.id;
  }

  private refresh(): void {
    const filters = this.aliasController.getFilters();
    this.filters = Object.keys(filters).map(id => filters[id]);
  }
}
