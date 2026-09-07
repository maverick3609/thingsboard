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
import { EMPTY, Observable } from 'rxjs';
import { expand, map, reduce } from 'rxjs/operators';
import { defaultHttpOptionsFromConfig, defaultHttpUploadOptions, RequestConfig } from '@core/http/http-utils';
import { AdoptControllerRequest, ControllerConfigSection, ControllerUploadKind,
  ControllerUploadStatus, DiscoveredController } from '@shared/models/inferrix-controller.models';
import { Device } from '@shared/models/device.models';

/**
 * HTTP client for /api/inferrix/controllers.
 *
 * Every call to a controller goes through the platform rather than the browser. The device serves a
 * per-device self-signed certificate with no CA behind it and sends no CORS headers, so a browser
 * cannot talk to it at all; the platform holds the pinned fingerprint and the bearer token.
 */
@Injectable({
  providedIn: 'root'
})
export class InferrixControllerService {

  constructor(private http: HttpClient) {}

  /**
   * A system administrator sees every announce on the network; a tenant administrator sees only the
   * controllers assigned to them. Assignments live in memory and are lost on a platform restart.
   */
  public getDiscoveredControllers(config?: RequestConfig): Observable<DiscoveredController[]> {
    return this.http.get<DiscoveredController[]>('/api/inferrix/controllers/discovered',
      defaultHttpOptionsFromConfig(config));
  }

  /** System administrator only — allocating hardware to a customer is not self-service. */
  public assignController(uid: string, tenantId: string, config?: RequestConfig): Observable<DiscoveredController> {
    return this.http.post<DiscoveredController>(
      `/api/inferrix/controllers/discovered/${uid}/assign?tenantId=${tenantId}`, null,
      defaultHttpOptionsFromConfig(config));
  }

  public adoptController(request: AdoptControllerRequest, config?: RequestConfig): Observable<Device> {
    return this.http.post<Device>('/api/inferrix/controllers/adopt', request,
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * Hands the platform a firmware image or a logic program and starts writing it to the device.
   *
   * Deliberately not a proxy call. The artifact has to be cut into pieces small enough for the
   * device's 2048-byte request cap — several hundred of them for an image — and the firmware closes
   * the connection after each one, so the loop runs on the platform and this returns a job to poll.
   */
  public uploadArtifact(deviceId: string, kind: ControllerUploadKind, file: File,
                        config?: RequestConfig): Observable<ControllerUploadStatus> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<ControllerUploadStatus>(
      `/api/inferrix/controllers/${deviceId}/upload/${kind.toLowerCase()}`, formData,
      defaultHttpUploadOptions(config?.ignoreLoading, config?.ignoreErrors, config?.resendRequest));
  }

  /**
   * The upload currently writing to this controller, or null.
   *
   * The job id lives only in the browser once an upload starts, so without this a page reload
   * leaves a device that refuses a second upload with nothing on screen to explain why.
   */
  public getActiveUpload(deviceId: string, config?: RequestConfig): Observable<ControllerUploadStatus> {
    return this.http.get<ControllerUploadStatus>(`/api/inferrix/controllers/${deviceId}/upload`,
      defaultHttpOptionsFromConfig(config));
  }

  /** Jobs are held on the node that accepted the upload and expire thirty minutes after it ends. */
  public getUploadStatus(jobId: string, config?: RequestConfig): Observable<ControllerUploadStatus> {
    return this.http.get<ControllerUploadStatus>(`/api/inferrix/controllers/uploads/${jobId}`,
      defaultHttpOptionsFromConfig(config));
  }

  /**
   * Reads one config section whole, following the device's paging.
   *
   * A section reply is capped at 2 KB and reports `truncated` with the section's real `total`, so
   * anything past the first page has to be walked with `offset`. The device also warns that paging
   * is by array index and is not a snapshot — a concurrent draft edit can shift records across a
   * page boundary — which is why the editor reads a section in one sweep and re-reads after every
   * write rather than patching its local copy.
   */
  public readConfigSection(deviceId: string, section: ControllerConfigSection,
                           draft: boolean): Observable<any[]> {
    const base = draft ? '/api/v1/config/draft' : '/api/v1/config';
    const fetchPage = (offset: number) =>
      this.proxy<any>(deviceId, 'GET', `${base}?section=${section.readSection}&offset=${offset}`,
        null, {ignoreErrors: true}).pipe(map(page => ({page, offset})));
    let pages = 0;
    return fetchPage(0).pipe(
      expand(({page, offset}) => {
        const records = recordsOf(page);
        // Three ways out, not one: the device says it is done, it stopped returning records, or it
        // has returned more pages than the largest section can hold. A device that answered
        // `truncated` forever would otherwise spin here.
        if (!page?.truncated || records.length === 0 || ++pages >= MAX_CONFIG_PAGES) {
          return EMPTY;
        }
        return fetchPage(offset + records.length);
      }),
      reduce((all, {page}) => all.concat(recordsOf(page)), [] as any[]));
  }

  /** Upsert is a full-record write; the device replaces by id or appends. */
  public upsertConfigRecord(deviceId: string, section: ControllerConfigSection,
                            record: any): Observable<any> {
    return this.proxy(deviceId, 'PUT', `/api/v1/config/draft/${section.crudPath}`, record,
      {ignoreErrors: true});
  }

  public deleteConfigRecord(deviceId: string, section: ControllerConfigSection,
                            id: number): Observable<any> {
    return this.proxy(deviceId, 'DELETE', `/api/v1/config/draft/${section.crudPath}/${id}`, null,
      {ignoreErrors: true});
  }

  /** Compiles, verifies, stages and hot-swaps the draft. Nothing is written if it is rejected. */
  public applyConfig(deviceId: string): Observable<{iccVersion: number; activation: string}> {
    return this.proxy(deviceId, 'POST', '/api/v1/config/apply', null, {ignoreErrors: true});
  }

  public discardConfig(deviceId: string): Observable<any> {
    return this.proxy(deviceId, 'POST', '/api/v1/config/discard', null, {ignoreErrors: true});
  }

  public getConfigOwner(deviceId: string): Observable<{owner: string}> {
    return this.proxy(deviceId, 'GET', '/api/v1/config/owner', null, {ignoreErrors: true});
  }

  public setConfigOwner(deviceId: string, owner: string): Observable<any> {
    return this.proxy(deviceId, 'PUT', '/api/v1/config/owner', {owner}, {ignoreErrors: true});
  }

  /**
   * Forwards one request to the controller's own REST API.
   *
   * `path` is a device path such as `/api/v1/health`. Only device-configuration routes are
   * forwarded: the auth routes are refused because calling them would revoke the token the platform
   * holds, and the binary firmware and logic uploads need their own chunked flow.
   */
  public proxy<T>(deviceId: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string,
                  body?: any, config?: RequestConfig): Observable<T> {
    const url = `/api/inferrix/controllers/${deviceId}/proxy${path}`;
    const options = defaultHttpOptionsFromConfig(config);
    switch (method) {
      case 'GET':
        return this.http.get<T>(url, options);
      case 'POST':
        return this.http.post<T>(url, body ?? null, options);
      case 'PUT':
        return this.http.put<T>(url, body ?? null, options);
      case 'DELETE':
        return this.http.delete<T>(url, options);
    }
  }
}

/** 1024 points at the buffer's worth per page leaves plenty of room; see readConfigSection. */
const MAX_CONFIG_PAGES = 256;

/**
 * The records out of one section page.
 *
 * Keyed on "the one array in the object" rather than on the section name, because the read name and
 * the CRUD path already disagree for the publish policies and there is no reason to depend on the
 * response key matching either of them.
 */
const recordsOf = (page: any): any[] => {
  if (!page) {
    return [];
  }
  const field = Object.keys(page).find(key => Array.isArray(page[key]));
  return field ? page[field] : [];
};
