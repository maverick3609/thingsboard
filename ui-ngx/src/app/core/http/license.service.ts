// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from './http-utils';
import { LicenseInfo } from '@shared/models/license.models';

@Injectable({ providedIn: 'root' })
export class LicenseService {

  constructor(private http: HttpClient) {}

  getLicenseInfo(config?: RequestConfig): Observable<LicenseInfo> {
    return this.http.get<LicenseInfo>('/api/license/info', defaultHttpOptionsFromConfig(config));
  }
}
