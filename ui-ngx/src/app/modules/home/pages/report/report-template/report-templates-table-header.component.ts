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

import { Component } from '@angular/core';
import { EntityTableHeaderComponent } from '@home/components/entity/entity-table-header.component';
import { ReportTemplateInfo, ReportTemplateType, TbReportFormat } from '@shared/models/report.models';
import { PageLink } from '@shared/models/page/page-link';
import { ReportTemplatesTableConfig } from '@home/pages/report/report-template/report-templates-table-config.resolver';

// The templates list's type/format filter, rendered into the table toolbar's header slot
// (EntityTableConfig#headerComponent, same hook AlarmTableHeaderComponent uses). Both facets are
// server-side query params, so a change just rewrites the config's filter object and re-runs the
// fetch - there is no client-side filtering here.
@Component({
  selector: 'tb-report-templates-table-header',
  templateUrl: './report-templates-table-header.component.html',
  standalone: false
})
export class ReportTemplatesTableHeaderComponent
  extends EntityTableHeaderComponent<ReportTemplateInfo, PageLink, ReportTemplateInfo, ReportTemplatesTableConfig> {

  reportTemplateTypes = Object.values(ReportTemplateType);
  reportFormats = Object.values(TbReportFormat);

  typeTranslations = new Map<ReportTemplateType, string>([
    [ReportTemplateType.REPORT, 'report.type-report'],
    [ReportTemplateType.SUB_REPORT, 'report.type-sub-report']
  ]);

  // Explicit public constructor: EntityTableHeaderComponent's is `protected`, and Angular refuses to
  // instantiate an inherited protected constructor for a declared component.
  constructor() {
    super();
  }

  get filterConfig() {
    return this.entitiesTableConfig.templatesFilterConfig;
  }

  get filterSet(): boolean {
    return !!(this.filterConfig.typeList?.length || this.filterConfig.formatList?.length);
  }

  isTypeSelected(type: ReportTemplateType): boolean {
    return !!this.filterConfig.typeList?.includes(type);
  }

  isFormatSelected(format: TbReportFormat): boolean {
    return !!this.filterConfig.formatList?.includes(format);
  }

  toggleType($event: Event, type: ReportTemplateType): void {
    $event.stopPropagation();
    this.filterConfig.typeList = toggle(this.filterConfig.typeList, type);
    this.reload();
  }

  toggleFormat($event: Event, format: TbReportFormat): void {
    $event.stopPropagation();
    this.filterConfig.formatList = toggle(this.filterConfig.formatList, format);
    this.reload();
  }

  reset($event: Event): void {
    $event.stopPropagation();
    this.filterConfig.typeList = [];
    this.filterConfig.formatList = [];
    this.reload();
  }

  // resetSortAndFilter(true, true) is what AlarmTableHeaderComponent uses for the same job: it
  // re-runs the fetch from page 0, which matters because the current page may not exist once the
  // result set shrinks.
  private reload(): void {
    this.entitiesTableConfig.getTable()?.resetSortAndFilter(true, true);
  }
}

function toggle<T>(list: T[] | undefined, value: T): T[] {
  const next = [...(list ?? [])];
  const index = next.indexOf(value);
  if (index >= 0) {
    next.splice(index, 1);
  } else {
    next.push(value);
  }
  return next;
}
