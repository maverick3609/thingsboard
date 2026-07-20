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
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { ResourcesService } from '@core/services/resources.service';
import {
  DashboardReportConfig,
  ReportInfo,
  ReportTemplate,
  ReportTemplateInfo,
  ReportTemplateType,
  TbReportFormat
} from '@shared/models/report.models';

// HTTP client for the /api/v2/report* + /api/reportTemplate* + /api/report/{dashboardId}/download
// REST surface (design spec §10, tasks 19-21). NOT to be confused with
// core/services/report.service.ts (Task 12), the browser-side reportView-protocol window-message
// driver - unrelated responsibility, different directory.
@Injectable({
  providedIn: 'root'
})
export class ReportService {

  constructor(
    private http: HttpClient,
    private resourcesService: ResourcesService
  ) {}

  public getReportInfos(pageLink: PageLink,
                         params: { reportTemplateId?: string; userId?: string; includeCustomers?: boolean } = {},
                         config?: RequestConfig): Observable<PageData<ReportInfo>> {
    let url = `/api/v2/reportInfos${pageLink.toQuery()}`;
    if (params.reportTemplateId) {
      url += `&reportTemplateId=${params.reportTemplateId}`;
    }
    if (params.userId) {
      url += `&userId=${params.userId}`;
    }
    if (params.includeCustomers !== undefined) {
      url += `&includeCustomers=${params.includeCustomers}`;
    }
    return this.http.get<PageData<ReportInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  // GET /api/v2/report/{reportId}/download - binary; mirrors the codebase's established
  // arraybuffer + x-filename download-and-trigger idiom (ResourcesService#downloadResource, also
  // used by device/image/ota-package/resource services).
  public downloadReport(reportId: string, config?: RequestConfig): Observable<any> {
    return this.resourcesService.downloadResource(`/api/v2/report/${reportId}/download`, config);
  }

  public deleteReport(reportId: string, config?: RequestConfig) {
    return this.http.delete(`/api/v2/report/${reportId}`, defaultHttpOptionsFromConfig(config));
  }

  public saveReportTemplate(reportTemplate: ReportTemplate, config?: RequestConfig): Observable<ReportTemplate> {
    return this.http.post<ReportTemplate>('/api/reportTemplate', reportTemplate, defaultHttpOptionsFromConfig(config));
  }

  public getReportTemplates(reportTemplateIds: Array<string>, config?: RequestConfig): Observable<Array<ReportTemplate>> {
    return this.http.get<Array<ReportTemplate>>(`/api/reportTemplates?reportTemplateIds=${reportTemplateIds.join(',')}`,
      defaultHttpOptionsFromConfig(config));
  }

  public getReportTemplateById(reportTemplateId: string, config?: RequestConfig): Observable<ReportTemplate> {
    return this.http.get<ReportTemplate>(`/api/reportTemplate/${reportTemplateId}`, defaultHttpOptionsFromConfig(config));
  }

  public deleteReportTemplate(reportTemplateId: string, config?: RequestConfig) {
    return this.http.delete(`/api/reportTemplate/${reportTemplateId}`, defaultHttpOptionsFromConfig(config));
  }

  public getReportTemplateInfos(pageLink: PageLink,
                                 params: { typeList?: Array<ReportTemplateType>; formatList?: Array<TbReportFormat>;
                                   includeCustomers?: boolean } = {},
                                 config?: RequestConfig): Observable<PageData<ReportTemplateInfo>> {
    let url = `/api/reportTemplateInfos/all${pageLink.toQuery()}`;
    if (params.typeList?.length) {
      url += `&typeList=${params.typeList.join(',')}`;
    }
    if (params.formatList?.length) {
      url += `&formatList=${params.formatList.join(',')}`;
    }
    if (params.includeCustomers !== undefined) {
      url += `&includeCustomers=${params.includeCustomers}`;
    }
    return this.http.get<PageData<ReportTemplateInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  // POST /api/report/{dashboardId}/download - binary; same arraybuffer + x-filename idiom as
  // ResourcesService#downloadResource, adapted for a POST-with-body request (that method only
  // supports GET), so it is reimplemented here rather than reused.
  public downloadDashboardReport(dashboardId: string, params: Partial<DashboardReportConfig>,
                                  config?: RequestConfig): Observable<any> {
    return this.http.post(`/api/report/${dashboardId}/download`, params, {
      ...defaultHttpOptionsFromConfig(config),
      responseType: 'arraybuffer',
      observe: 'response'
    }).pipe(
      map((response: HttpResponse<ArrayBuffer>) => {
        const headers = response.headers;
        const filename = headers.get('x-filename');
        const contentType = headers.get('content-type');
        const blob = new Blob([response.body], {type: contentType});
        const url = URL.createObjectURL(blob);
        const linkElement = document.createElement('a');
        linkElement.setAttribute('href', url);
        linkElement.setAttribute('download', filename);
        const clickEvent = new MouseEvent('click', {view: window, bubbles: true, cancelable: false});
        linkElement.dispatchEvent(clickEvent);
        return null;
      })
    );
  }
}
