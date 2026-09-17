// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import {
  SchedulerEvent,
  SchedulerEventWithCustomerInfo
} from '@shared/models/scheduler-event.models';

@Injectable({
  providedIn: 'root'
})
export class SchedulerEventService {

  constructor(private http: HttpClient) {}

  public getSchedulerEvents(pageLink: PageLink, type?: string, config?: RequestConfig):
      Observable<PageData<SchedulerEventWithCustomerInfo>> {
    let url = `/api/schedulerEvents${pageLink.toQuery()}`;
    if (type) {
      url += `&type=${type}`;
    }
    return this.http.get<PageData<SchedulerEventWithCustomerInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getAllSchedulerEvents(type?: string, config?: RequestConfig): Observable<Array<SchedulerEventWithCustomerInfo>> {
    const url = type ? `/api/schedulerEvents?type=${type}` : '/api/schedulerEvents';
    return this.http.get<Array<SchedulerEventWithCustomerInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getSchedulerEventsByTimeWindow(startTime: number, endTime: number, type?: string, config?: RequestConfig):
      Observable<Array<SchedulerEventWithCustomerInfo>> {
    let url = `/api/schedulerEvents?startTime=${startTime}&endTime=${endTime}`;
    if (type) {
      url += `&type=${type}`;
    }
    return this.http.get<Array<SchedulerEventWithCustomerInfo>>(url, defaultHttpOptionsFromConfig(config));
  }

  public getSchedulerEventInfo(schedulerEventId: string, config?: RequestConfig): Observable<SchedulerEventWithCustomerInfo> {
    return this.http.get<SchedulerEventWithCustomerInfo>(`/api/schedulerEvent/info/${schedulerEventId}`,
      defaultHttpOptionsFromConfig(config));
  }

  public getSchedulerEvent(schedulerEventId: string, config?: RequestConfig): Observable<SchedulerEvent> {
    return this.http.get<SchedulerEvent>(`/api/schedulerEvent/${schedulerEventId}`, defaultHttpOptionsFromConfig(config));
  }

  public saveSchedulerEvent(schedulerEvent: SchedulerEvent, config?: RequestConfig): Observable<SchedulerEvent> {
    return this.http.post<SchedulerEvent>('/api/schedulerEvent', schedulerEvent, defaultHttpOptionsFromConfig(config));
  }

  public setSchedulerEventEnabled(schedulerEventId: string, enabled: boolean, config?: RequestConfig): Observable<SchedulerEvent> {
    return this.http.put<SchedulerEvent>(`/api/schedulerEvent/${schedulerEventId}/enabled/${enabled}`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public deleteSchedulerEvent(schedulerEventId: string, config?: RequestConfig) {
    return this.http.delete(`/api/schedulerEvent/${schedulerEventId}`, defaultHttpOptionsFromConfig(config));
  }
}
