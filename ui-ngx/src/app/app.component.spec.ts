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
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { TranslateService } from '@ngx-translate/core';

import { AppComponent } from './app.component';
import { LocalStorageService } from '@core/local-storage/local-storage.service';
import { AuthService } from '@core/auth/auth.service';
import { ReportService } from '@core/services/report.service';
import { selectAuth, selectUserReady } from '@core/auth/auth.selectors';
import { AuthState } from '@core/auth/auth.models';

/**
 * BUG 1 (report-view auth-redirect guard) regression spec.
 *
 * AppComponent.setupAuth()'s post-skip(1) subscribe used to call authService.gotoDefaultPlace() on
 * every further selectUserReady emission with isUserLoaded===true. During a headless report
 * render, the report-view tab's own openReport re-authentication
 * (report.service.ts#loadUser -> setUserFromJwtToken) synchronously redrives this exact stream past
 * the skip(1) budget - including one {isUserLoaded: true, isAuthenticated: false} emission from a
 * spurious notifyUnauthenticated() dispatch - which used to send gotoDefaultPlace(false) racing
 * against openReport's own navigateByUrl to the target dashboard. The fix guards the single
 * subscribe choke point behind `!reportService.active`, true only for the ?reportView=true tab
 * (set once in ReportService#loadReportParams(), never toggled again) - so this spec proves BOTH
 * that the stray call is suppressed in report-view mode AND that normal (non-report-view) sessions
 * still get gotoDefaultPlace() exactly as before.
 */
describe('AppComponent', () => {
  let store: MockStore;
  let authService: jasmine.SpyObj<AuthService>;
  let reportService: { active: boolean; loadReportParams: jasmine.Spy };

  function emitUserReady(isAuthenticated: boolean): void {
    store.overrideSelector(selectUserReady, {isUserLoaded: true, isAuthenticated});
    store.refreshState();
  }

  beforeEach(() => {
    authService = jasmine.createSpyObj('AuthService', ['gotoDefaultPlace', 'reloadUser']);
    reportService = {active: false, loadReportParams: jasmine.createSpy('loadReportParams')};

    TestBed.configureTestingModule({
      providers: [
        AppComponent,
        provideMockStore({
          selectors: [
            {selector: selectUserReady, value: {isUserLoaded: false, isAuthenticated: false}},
            // getCurrentAuthState() (called from the setupAuth() tap()) reads this selector
            // directly; without an override it resolves against the mock's empty root state and
            // `.userDetails` access throws.
            {selector: selectAuth, value: {} as AuthState}
          ]
        }),
        {provide: LocalStorageService, useValue: jasmine.createSpyObj('LocalStorageService', ['testLocalStorage', 'getItem'])},
        {provide: TranslateService, useValue: jasmine.createSpyObj('TranslateService', ['addLangs', 'setFallbackLang'])},
        {
          provide: MatIconRegistry,
          useValue: jasmine.createSpyObj('MatIconRegistry', ['addSvgIconResolver', 'addSvgIconLiteral', 'addSvgIcon'])
        },
        {
          provide: DomSanitizer,
          useValue: jasmine.createSpyObj('DomSanitizer', ['bypassSecurityTrustResourceUrl', 'bypassSecurityTrustHtml'])
        },
        {provide: AuthService, useValue: authService},
        {provide: ReportService, useValue: reportService}
      ]
    });

    store = TestBed.inject(MockStore);
  });

  it('calls gotoDefaultPlace on the first post-bootstrap isUserLoaded emission for a normal (non-report-view) session', () => {
    reportService.active = false;
    TestBed.inject(AppComponent); // constructor runs setupAuth(): subscribes, then reloadUser()/loadReportParams()

    emitUserReady(true); // 1st post-filter emission - consumed by skip(1), same as today
    expect(authService.gotoDefaultPlace).not.toHaveBeenCalled();

    emitUserReady(false); // 2nd post-filter emission - reaches the subscribe callback
    expect(authService.gotoDefaultPlace).toHaveBeenCalledWith(false);
    expect(authService.gotoDefaultPlace).toHaveBeenCalledTimes(1);
  });

  it('suppresses the stray gotoDefaultPlace once reportService.active is true (report-view tab)', () => {
    reportService.active = true;
    TestBed.inject(AppComponent);

    emitUserReady(true);  // consumed by skip(1)
    emitUserReady(false); // would reach subscribe, but reportService.active===true guards it

    expect(authService.gotoDefaultPlace).not.toHaveBeenCalled();
  });
});
