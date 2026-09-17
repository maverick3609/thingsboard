// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { shareReplay } from 'rxjs/operators';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { AllowedPermissionsInfo, Role } from '@shared/models/role.models';

@Injectable({
  providedIn: 'root'
})
export class RoleService {

  private allowedPermissions$: Observable<AllowedPermissionsInfo>;

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

  // The resource/operation vocabularies are constant for a server build, so the role editor
  // should not re-fetch them on every dialog open. Note the response also carries the acting
  // user's userPermissions: that part is a session snapshot, so do not gate UI on it — the menu
  // gating reads authState.userPermissions, which /api/system/params refreshes on every load.
  public getAllowedPermissions(config?: RequestConfig): Observable<AllowedPermissionsInfo> {
    if (!this.allowedPermissions$) {
      this.allowedPermissions$ = this.http.get<AllowedPermissionsInfo>('/api/permissions/allowedPermissions',
        defaultHttpOptionsFromConfig(config)).pipe(shareReplay({bufferSize: 1, refCount: false}));
    }
    return this.allowedPermissions$;
  }

}
