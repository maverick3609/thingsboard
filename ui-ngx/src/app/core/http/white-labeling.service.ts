///
/// Copyright © 2016-2026 The Thingsboard Authors
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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from './http-utils';
import { LoginWhiteLabelingParams, WhiteLabelingParams } from '@shared/models/white-labeling.models';

@Injectable({ providedIn: 'root' })
export class WhiteLabelingHttpService {

  constructor(private http: HttpClient) {}

  getWhiteLabelParams(config?: RequestConfig): Observable<WhiteLabelingParams> {
    return this.http.get<WhiteLabelingParams>('/api/whiteLabel/whiteLabelParams',
      defaultHttpOptionsFromConfig(config));
  }

  getLoginWhiteLabelParams(config?: RequestConfig): Observable<LoginWhiteLabelingParams> {
    return this.http.get<LoginWhiteLabelingParams>('/api/noauth/whiteLabel/loginWhiteLabelParams',
      defaultHttpOptionsFromConfig(config));
  }

  getCurrentWhiteLabelParams(customerId?: string, config?: RequestConfig): Observable<WhiteLabelingParams> {
    let url = '/api/whiteLabel/currentWhiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.get<WhiteLabelingParams>(url, defaultHttpOptionsFromConfig(config));
  }

  getCurrentLoginWhiteLabelParams(customerId?: string, config?: RequestConfig): Observable<LoginWhiteLabelingParams> {
    let url = '/api/whiteLabel/currentLoginWhiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.get<LoginWhiteLabelingParams>(url, defaultHttpOptionsFromConfig(config));
  }

  saveWhiteLabelParams(params: WhiteLabelingParams, customerId?: string, config?: RequestConfig): Observable<WhiteLabelingParams> {
    let url = '/api/whiteLabel/whiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.post<WhiteLabelingParams>(url, params, defaultHttpOptionsFromConfig(config));
  }

  saveLoginWhiteLabelParams(params: LoginWhiteLabelingParams, customerId?: string, config?: RequestConfig): Observable<LoginWhiteLabelingParams> {
    let url = '/api/whiteLabel/loginWhiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.post<LoginWhiteLabelingParams>(url, params, defaultHttpOptionsFromConfig(config));
  }

  previewWhiteLabelParams(params: WhiteLabelingParams, config?: RequestConfig): Observable<WhiteLabelingParams> {
    return this.http.post<WhiteLabelingParams>('/api/whiteLabel/previewWhiteLabelParams',
      params, defaultHttpOptionsFromConfig(config));
  }

  isWhiteLabelingAllowed(config?: RequestConfig): Observable<boolean> {
    return this.http.get<boolean>('/api/whiteLabel/isWhiteLabelingAllowed',
      defaultHttpOptionsFromConfig(config));
  }

  isCustomerWhiteLabelingAllowed(config?: RequestConfig): Observable<boolean> {
    return this.http.get<boolean>('/api/whiteLabel/isCustomerWhiteLabelingAllowed',
      defaultHttpOptionsFromConfig(config));
  }

  deleteCurrentWhiteLabelParams(customerId?: string, config?: RequestConfig): Observable<void> {
    let url = '/api/whiteLabel/currentWhiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.delete<void>(url, defaultHttpOptionsFromConfig(config));
  }

  deleteCurrentLoginWhiteLabelParams(customerId?: string, config?: RequestConfig): Observable<void> {
    let url = '/api/whiteLabel/currentLoginWhiteLabelParams';
    if (customerId) url += `?customerId=${customerId}`;
    return this.http.delete<void>(url, defaultHttpOptionsFromConfig(config));
  }

  // Privacy Policy

  getPrivacyPolicy(config?: RequestConfig): Observable<any> {
    return this.http.get<any>('/api/whiteLabel/privacyPolicy', defaultHttpOptionsFromConfig(config));
  }

  getWebPrivacyPolicy(config?: RequestConfig): Observable<any> {
    return this.http.get<any>('/api/noauth/whiteLabel/privacyPolicy', defaultHttpOptionsFromConfig(config));
  }

  savePrivacyPolicy(policy: any, config?: RequestConfig): Observable<any> {
    return this.http.post<any>('/api/whiteLabel/privacyPolicy', policy, defaultHttpOptionsFromConfig(config));
  }

  // Terms of Use

  getTermsOfUse(config?: RequestConfig): Observable<any> {
    return this.http.get<any>('/api/whiteLabel/termsOfUse', defaultHttpOptionsFromConfig(config));
  }

  getWebTermsOfUse(config?: RequestConfig): Observable<any> {
    return this.http.get<any>('/api/noauth/whiteLabel/termsOfUse', defaultHttpOptionsFromConfig(config));
  }

  saveTermsOfUse(terms: any, config?: RequestConfig): Observable<any> {
    return this.http.post<any>('/api/whiteLabel/termsOfUse', terms, defaultHttpOptionsFromConfig(config));
  }
}
