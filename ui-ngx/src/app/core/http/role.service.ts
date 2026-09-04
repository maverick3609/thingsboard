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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { AllowedPermissionsInfo, Role } from '@shared/models/role.models';

@Injectable({
  providedIn: 'root'
})
export class RoleService {

  constructor(private http: HttpClient) {}

  public getRoles(pageLink: PageLink, config?: RequestConfig): Observable<PageData<Role>> {
    return this.http.get<PageData<Role>>(`/api/roles${pageLink.toQuery()}`, defaultHttpOptionsFromConfig(config));
  }

  public getRole(roleId: string, config?: RequestConfig): Observable<Role> {
    return this.http.get<Role>(`/api/role/${roleId}`, defaultHttpOptionsFromConfig(config));
  }

  public saveRole(role: Role, config?: RequestConfig): Observable<Role> {
    return this.http.post<Role>('/api/role', role, defaultHttpOptionsFromConfig(config));
  }

  public deleteRole(roleId: string, config?: RequestConfig): Observable<any> {
    return this.http.delete(`/api/role/${roleId}`, defaultHttpOptionsFromConfig(config));
  }

  public getUserRoles(userId: string, config?: RequestConfig): Observable<Array<Role>> {
    return this.http.get<Array<Role>>(`/api/user/${userId}/roles`, defaultHttpOptionsFromConfig(config));
  }

  public updateUserRoles(userId: string, roleIds: string[], config?: RequestConfig): Observable<any> {
    return this.http.post(`/api/user/${userId}/roles`, roleIds, defaultHttpOptionsFromConfig(config));
  }

  public getAllowedPermissions(config?: RequestConfig): Observable<AllowedPermissionsInfo> {
    return this.http.get<AllowedPermissionsInfo>('/api/permissions/allowedPermissions', defaultHttpOptionsFromConfig(config));
  }

}
