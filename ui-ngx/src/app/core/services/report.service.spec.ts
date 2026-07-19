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

import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideMockStore } from '@ngrx/store/testing';
import { of } from 'rxjs';
import { DOCUMENT } from '@angular/common';

import { ReportService, ReportResult } from './report.service';
import { WINDOW } from '@core/services/window.service';
import { UtilsService } from '@core/services/utils.service';
import { AuthService } from '@core/auth/auth.service';
import { selectUserReady } from '@core/auth/auth.selectors';

/**
 * Proves the report-view message protocol end to end against a real DOM (ChromeHeadless), not
 * mocked DOM predicates: given an `openReport` command and a simulated dashboard-loaded event,
 * ReportService drives navigation, waits on the real readiness predicates, and reports back via
 * `window.postWebReportResult`.
 *
 * currentPerfTime() (and PE's own predicates) read wall-clock time (performance.now()), which
 * zone.js's fakeAsync/tick() do not virtualize. These specs therefore use real timers/real waits
 * (short ones - tens to a few hundred ms) rather than fakeAsync, so the settle/timeout thresholds
 * being asserted are the actual ones that will run in production.
 */
describe('ReportService', () => {
  let service: ReportService;
  let router: jasmine.SpyObj<Router>;
  let authService: jasmine.SpyObj<AuthService>;
  let fakeUtils: { reportView: string; getQueryParam: jasmine.Spy; guid: jasmine.Spy; currentPerfTime: jasmine.Spy };
  let domFixture: HTMLElement;

  function appendDashboardDom(): HTMLElement {
    const section = document.createElement('section');
    section.className = 'tb-dashboard-container';
    const gridster = document.createElement('gridster');
    gridster.id = 'gridster-child';
    section.appendChild(gridster);
    document.body.appendChild(section);
    return section;
  }

  async function waitFor(predicate: () => boolean, timeoutMs = 2000, stepMs = 15): Promise<void> {
    const start = Date.now();
    while (!predicate()) {
      if (Date.now() - start > timeoutMs) {
        throw new Error('waitFor: condition never became true within ' + timeoutMs + 'ms');
      }
      await new Promise<void>(resolve => setTimeout(resolve, stepMs));
    }
  }

  beforeEach(() => {
    fakeUtils = {
      reportView: 'true',
      getQueryParam: jasmine.createSpy('getQueryParam').and.callFake((name: string) =>
        name === 'reportView' ? fakeUtils.reportView : null),
      guid: jasmine.createSpy('guid').and.callFake(() => 'id-' + Math.random().toString(16).slice(2)),
      currentPerfTime: jasmine.createSpy('currentPerfTime').and.callFake(() => performance.now())
    };

    router = jasmine.createSpyObj('Router', ['navigateByUrl']);
    authService = jasmine.createSpyObj('AuthService', ['setUserFromJwtToken', 'publicLogin', 'logout']);

    TestBed.configureTestingModule({
      providers: [
        ReportService,
        { provide: WINDOW, useValue: window },
        { provide: DOCUMENT, useValue: document },
        { provide: UtilsService, useValue: fakeUtils },
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: authService },
        provideMockStore({
          selectors: [
            { selector: selectUserReady, value: { isUserLoaded: true, isAuthenticated: true } }
          ]
        })
      ]
    });

    service = TestBed.inject(ReportService);
    delete (window as any).postWebReportResult;
  });

  afterEach(() => {
    if (domFixture) {
      domFixture.remove();
      domFixture = undefined;
    }
    delete (window as any).postWebReportResult;
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('loadReportParams returns false and stays inactive when reportView is not "true"', () => {
    fakeUtils.reportView = null;
    const active = service.loadReportParams();
    expect(active).toBe(false);
    expect(service.active).toBe(false);
  });

  it('loadReportParams signals postWebReportResult once the user-ready state settles', async () => {
    const posted: ReportResult[] = [];
    (window as any).postWebReportResult = (r: ReportResult) => posted.push(r);

    const active = service.loadReportParams();

    expect(active).toBe(true);
    await waitFor(() => posted.length > 0);
    expect(posted[0]).toEqual({success: true});
  });

  it('replies success with a numeric pageHeight once layout ready (openReport -> handleMessage)', async () => {
    domFixture = appendDashboardDom();
    router.navigateByUrl.and.returnValue(Promise.resolve(true));
    authService.setUserFromJwtToken.and.returnValue(of(true));

    const posted: ReportResult[] = [];
    (window as any).postWebReportResult = (r: ReportResult) => posted.push(r);

    service.handleMessage({type: 'openReport', data: {accessToken: 'tkn', dashboardId: 'd1'}} as any);
    // Simulates dashboard-page.component.ts firing the parallel reportService.onDashboardLoaded(...)
    // hook once the new route's widgets have mounted (0 widgets on this fixture).
    service.onDashboardLoaded(0);

    await waitFor(() => posted.length > 0, 3000);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard/d1', {replaceUrl: true});
    expect(posted[0].success).toBe(true);
    expect(typeof posted[0].pageHeight).toBe('number');
  });

  it('rejects with the verbatim PE timeout string when the dashboard DOM never becomes ready', async () => {
    // No DOM fixture appended: section.tb-dashboard-container/gridster#gridster-child never appears,
    // so isReportPageDomReady() can never settle - proves the predicate is real, not faked.
    router.navigateByUrl.and.returnValue(Promise.resolve(true));
    authService.setUserFromJwtToken.and.returnValue(of(true));

    const posted: ReportResult[] = [];
    (window as any).postWebReportResult = (r: ReportResult) => posted.push(r);

    service.handleMessage({
      type: 'openReport',
      data: {accessToken: 'tkn', dashboardId: 'd1', timeout: 60}
    } as any);
    service.onDashboardLoaded(0);

    await waitFor(() => posted.length > 0, 3000);

    expect(posted[0].success).toBe(false);
    expect(posted[0].error).toBe('Wait for report page timed out!');
  });

  it('handleMessage(openReport) reports "Invalid message arguments provided!" without accessToken/publicId', async () => {
    const posted: ReportResult[] = [];
    (window as any).postWebReportResult = (r: ReportResult) => posted.push(r);

    service.handleMessage({type: 'openReport', data: {dashboardId: 'd1'}} as any);

    await waitFor(() => posted.length > 0);
    expect(posted[0]).toEqual({success: false, error: 'Invalid message arguments provided!'});
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
