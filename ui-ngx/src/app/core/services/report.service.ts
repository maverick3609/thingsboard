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

import { Inject, Injectable } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import $ from 'jquery';
import { from, Observable, of, Subject } from 'rxjs';
import { catchError, filter, map, switchMap, take, tap } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { WINDOW } from '@core/services/window.service';
import { UtilsService } from '@core/services/utils.service';
import { AuthService } from '@core/auth/auth.service';
import { selectUserReady } from '@core/auth/auth.selectors';
import { Timewindow } from '@shared/models/time/time.models';

/**
 * Browser-side driver for the "report view" render-mode handshake that the (server-side)
 * Playwright renderer speaks to a headless Chromium tab. Reproduces ThingsBoard PE's
 * `ReportService` (decompiled from `ui-ngx-4.2.0PE.jar`) against CE's dashboard/widget/websocket
 * internals. See docs/specs/2026-07-17-reporting-design.md §6.3 for the protocol description;
 * the ready-signal error strings and the pageHeight formula below are PE-verbatim.
 *
 * Bootstrapped from AppComponent when `?reportView=true` is present (see loadReportParams()).
 * NOTE: this is the UI-side protocol driver, not the report HTTP client (core/http/report.service.ts).
 */
export type ReportMessageType = 'openReport' | 'waitReportWidgets' | 'clearReport' | 'reportResult';

export interface ReportMessage {
  type: ReportMessageType;
  data?: any;
}

export interface OpenReportCommand {
  accessToken?: string;
  publicId?: string;
  dashboardId: string;
  state?: string;
  reportTimewindow?: Timewindow;
  timeout?: number;
}

export interface WaitReportWidgetsCommand {
  timeout?: number;
}

export interface ReportResult {
  success: boolean;
  pageHeight?: number;
  error?: any;
}

// PE navigates the openReport/clearReport handshake to its own `/empty-page` route between
// renders of a long-lived tab. CE has no equivalent blank route, and the Task 13 renderer uses
// one BrowserContext per render (closed after use) rather than reusing a tab, so clearReport's
// only real job is to unmount the current dashboard; the app's default authenticated route does
// that without inventing new CE surface.
const REPORT_CLEAR_URL = '/';

@Injectable({
  providedIn: 'root'
})
export class ReportService {

  public reportView = false;
  public reportTimewindow: Timewindow = null;
  public publicId: string = null;

  /** Fires whenever an `openReport` command starts navigating; PE parity, no CE consumer yet. */
  public readonly openReportSubject = new Subject<void>();

  private accessToken: string = null;

  private readonly onWindowMessageListener = this.onWindowMessage.bind(this);

  private widgetsCount = 0;
  private lastWaitWidgetsTimeMs = 0;

  private receiveWsData = new Map<number, boolean>();
  private lastWsCommandTimeMs = 0;

  private waitForWidgets = new Set<string>();
  private lastWaitWidgetTimeMs = 0;

  constructor(@Inject(WINDOW) private window: Window,
              @Inject(DOCUMENT) private document: Document,
              private utils: UtilsService,
              private router: Router,
              private authService: AuthService,
              private store: Store<AppState>) {
  }

  public get active(): boolean {
    return this.reportView;
  }

  /**
   * Called once from AppComponent.setupAuth(). Mirrors PE's
   * `loadReportParams() { return getQueryParam('reportView')==='true' && (... ) , this.reportView }`:
   * detects report mode synchronously, then (async) waits for the same user-ready signal
   * AppComponent itself waits on before installing the postMessage listener and signalling
   * `postWebReportResult` availability.
   */
  public loadReportParams(): boolean {
    this.reportView = this.utils.getQueryParam('reportView') === 'true';
    if (this.reportView) {
      this.store.select(selectUserReady).pipe(
        filter(data => data.isUserLoaded),
        take(1)
      ).subscribe(() => {
        this.installReportListener();
      }, (err) => {
        console.error('[Reporting] loadReportParams failed', err);
      });
    }
    return this.reportView;
  }

  private installReportListener(): void {
    this.window.addEventListener('message', this.onWindowMessageListener);
    if ((this.window as any).postWebReportResult) {
      this.postReportResult({success: true});
    } else {
      // Playwright's page.exposeFunction('postWebReportResult', ...) may not have landed yet;
      // PE polls every 20ms until it does (§6.3 point 1).
      const intervalId = setInterval(() => {
        if ((this.window as any).postWebReportResult) {
          clearInterval(intervalId);
          this.postReportResult({success: true});
        }
      }, 20);
    }
  }

  // ----- readiness signal producers (called from the host-file hooks) -----

  /** dashboard-page.component.ts: parallel call to the existing mobileService.onDashboardLoaded(...) sites. */
  public onDashboardLoaded(widgetsCount: number): void {
    this.widgetsCount = widgetsCount;
    this.lastWaitWidgetsTimeMs = this.utils.currentPerfTime();
  }

  /** telemetry-websocket.service.ts: register a freshly-assigned, non-unsubscribe cmdId. */
  public onSendWsCommand(cmdId: number): void {
    if (typeof cmdId === 'number' && !this.receiveWsData.has(cmdId)) {
      this.receiveWsData.set(cmdId, false);
      this.lastWsCommandTimeMs = this.utils.currentPerfTime();
    }
  }

  /** telemetry-websocket.service.ts: mark a tracked cmdId/subscriptionId as having received data. */
  public onWsCmdUpdateMessage(message: { cmdId?: number; subscriptionId?: number }): void {
    if (message?.cmdId) {
      this.receiveWsData.set(message.cmdId, true);
    } else if (message?.subscriptionId) {
      this.receiveWsData.set(message.subscriptionId, true);
    }
  }

  /** map widget base (map.ts): register before tiles/overlay start loading. */
  public onWaitForWidget(): string {
    const id = this.utils.guid();
    this.waitForWidgets.add(id);
    this.lastWaitWidgetTimeMs = this.utils.currentPerfTime();
    return id;
  }

  public onWaitForMap(): string {
    return this.onWaitForWidget();
  }

  public onWidgetLoaded(id: string): void {
    this.waitForWidgets.delete(id);
  }

  /** map widget base (geo-map.ts / image-map.ts): complete once tiles/overlay fire 'load'. */
  public onMapLoaded(id: string): void {
    this.onWidgetLoaded(id);
  }

  // ----- window message protocol (renderer -> UI) -----

  public onWindowMessage(event: MessageEvent): void {
    if (event?.data) {
      let message: ReportMessage;
      try {
        message = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
      } catch (e) {
        console.error('[Reporting] onWindowMessage parse failed', e);
        return;
      }
      this.handleMessage(message);
    }
  }

  /** Parsed-message dispatcher; split out from onWindowMessage so it's directly unit-testable. */
  public handleMessage(message: ReportMessage): void {
    if (!message?.type) {
      return;
    }
    switch (message.type) {
      case 'openReport':
        this.openReport(message.data as OpenReportCommand).subscribe(
          (result) => this.postReportResult(result),
          (err) => {
            console.error('[Reporting] openReport handler failed', err);
            this.postReportResult({success: false, error: err?.message ?? String(err)});
          }
        );
        break;
      case 'waitReportWidgets': {
        const data = message.data as WaitReportWidgetsCommand;
        this.waitWidgets(data?.timeout).subscribe(
          (result) => this.postReportResult(result),
          (err) => {
            console.error('[Reporting] waitReportWidgets handler failed', err);
            this.postReportResult({success: false, error: err?.message ?? String(err)});
          }
        );
        break;
      }
      case 'clearReport':
        this.clearReport().subscribe(
          (success) => {
            const result: ReportResult = {success};
            if (!success) {
              result.error = 'Navigation failed while clear report!';
            }
            this.postReportResult(result);
          },
          (err) => {
            console.error('[Reporting] clearReport handler failed', err);
            this.postReportResult({success: false, error: err?.message ?? String(err)});
          }
        );
        break;
    }
  }

  private postReportResult(result: ReportResult): void {
    const reporter = (this.window as any).postWebReportResult;
    if (reporter) {
      reporter(result);
    } else {
      // Fallback channel (§6.3 point 6) if page.exposeFunction was never injected. Targeted at the
      // window's own origin (not '*'): the listener is a same-page Playwright page.evaluate
      // injection, never a cross-origin iframe, so there is no reason to broadcast off-origin.
      this.window.postMessage(JSON.stringify({type: 'reportResult', data: result}), this.window.location.origin);
    }
  }

  // ----- commands -----

  public openReport(command: OpenReportCommand): Observable<ReportResult> {
    if (!command || !(command.accessToken || command.publicId) || !command.dashboardId) {
      return of({success: false, error: 'Invalid message arguments provided!'});
    }
    return this.loadUser(command.accessToken, command.publicId).pipe(
      switchMap((loggedIn) => {
        if (!loggedIn) {
          return of({success: false, error: 'Authentication failed!'} as ReportResult);
        }
        this.reportTimewindow = command.reportTimewindow || null;
        let url = `/dashboard/${command.dashboardId}`;
        if (command.state) {
          url += `?state=${command.state}`;
        }
        this.openReportSubject.next();
        this.widgetsCount = 0;
        this.receiveWsData.clear();
        this.waitForWidgets.clear();
        this.lastWaitWidgetsTimeMs = 0;
        this.lastWsCommandTimeMs = this.utils.currentPerfTime();
        this.lastWaitWidgetTimeMs = this.utils.currentPerfTime();
        return from(this.router.navigateByUrl(url, {replaceUrl: true})).pipe(
          switchMap((navigated) => navigated
            ? this.waitForLayoutReady(command.timeout).pipe(
              map(() => ({success: true, pageHeight: this.pageHeight()} as ReportResult)),
              catchError((err) => {
                console.error('[Reporting] openReport waitForLayoutReady failed', err);
                return of({success: false, error: err} as ReportResult);
              })
            )
            : of({success: false, error: 'Failed to navigate to target dashboard!'} as ReportResult)
          ),
          catchError((err) => {
            console.error('[Reporting] openReport navigateByUrl failed', err);
            return of({success: false, error: err?.error?.message ?? err} as ReportResult);
          })
        );
      }),
      catchError((err) => {
        console.error('[Reporting] openReport loadUser failed', err);
        return of({success: false, error: 'Authentication failed!'} as ReportResult);
      })
    );
  }

  public waitWidgets(timeout = 3000): Observable<ReportResult> {
    return this.waitForWidgetsLoaded(timeout).pipe(
      switchMap(() => this.waitForLayoutReady(timeout)),
      switchMap(() => this.waitForWidgetsLoaded(timeout)),
      map(() => ({success: true} as ReportResult)),
      catchError((err) => {
        console.error('[Reporting] waitWidgets failed', err);
        return of({success: false, error: err} as ReportResult);
      })
    );
  }

  public clearReport(): Observable<boolean> {
    if (this.publicId) {
      this.publicId = null;
      this.authService.logout();
      return of(true);
    }
    return from(this.router.navigateByUrl(REPORT_CLEAR_URL, {replaceUrl: true})).pipe(
      catchError((err) => {
        console.error('[Reporting] clearReport navigation failed', err);
        return of(false);
      })
    );
  }

  /**
   * Re-authenticates the current tab as an explicit accessToken/publicId without a full page
   * navigation (used when an `openReport` command targets a different report/user than the one
   * that logged the tab in via the bootstrap `?accessToken=` URL). Mirrors PE's ReportService
   * `loadUser(accessToken, publicId)`, built on AuthService's existing public
   * setUserFromJwtToken/publicLogin primitives (the same ones MobileService's postMessage
   * `reloadUserMessage` handler uses) rather than the URL-query-param-only bootstrap path.
   */
  private loadUser(accessToken: string, publicId: string): Observable<boolean> {
    if (publicId) {
      if (this.publicId === publicId) {
        return of(true);
      }
      return this.authService.publicLogin(publicId).pipe(
        switchMap((response) => this.authService.setUserFromJwtToken(response.token, response.refreshToken, true)),
        tap((loaded) => {
          if (loaded) {
            this.publicId = publicId;
            this.accessToken = null;
          }
        }),
        catchError((err) => {
          console.error('[Reporting] loadUser publicId login failed', err);
          return of(false);
        })
      );
    }
    if (this.accessToken === accessToken) {
      return of(true);
    }
    return this.authService.setUserFromJwtToken(accessToken, null, true).pipe(
      tap((loaded) => {
        if (loaded) {
          this.accessToken = accessToken;
          this.publicId = null;
        }
      }),
      catchError((err) => {
        console.error('[Reporting] loadUser accessToken login failed', err);
        return of(false);
      })
    );
  }

  // ----- pageHeight + readiness predicates (PE §6.3 — verbatim thresholds & error strings) -----

  private pageHeight(): number {
    let height = 0;
    const gridsterChild = this.document.getElementById('gridster-child');
    if (gridsterChild) {
      height = Math.round(gridsterChild.scrollHeight);
      const title = this.document.querySelector('.tb-dashboard-title') as HTMLElement;
      if (title) {
        height += Math.round(title.offsetHeight);
      }
    }
    return height;
  }

  private waitForLayoutReady(timeout = 3000): Observable<void> {
    return this.waitForReportPage(timeout).pipe(
      switchMap(() => this.waitForWebsocketData(timeout))
    );
  }

  private waitForReportPage(timeout = 3000): Observable<void> {
    return new Observable<void>((subscriber) => {
      let elapsedMs = 0;
      const intervalId = setInterval(() => {
        if (this.lastWaitWidgetsTimeMs && this.isReportPageDomReady() &&
          this.utils.currentPerfTime() - this.lastWaitWidgetsTimeMs >= 300) {
          clearInterval(intervalId);
          subscriber.next();
          subscriber.complete();
        } else {
          elapsedMs += 10;
          if (elapsedMs >= timeout) {
            clearInterval(intervalId);
            subscriber.error('Wait for report page timed out!');
          }
        }
      }, 10);
      return () => clearInterval(intervalId);
    });
  }

  private waitForWebsocketData(timeout = 3000): Observable<void> {
    return new Observable<void>((subscriber) => {
      let elapsedMs = 0;
      const intervalId = setInterval(() => {
        const allReceived = !this.receiveWsData.size || Array.from(this.receiveWsData.values()).every(received => received);
        if (allReceived && this.utils.currentPerfTime() - this.lastWsCommandTimeMs >= 100) {
          clearInterval(intervalId);
          subscriber.next();
          subscriber.complete();
        } else {
          elapsedMs += 10;
          if (elapsedMs >= timeout) {
            clearInterval(intervalId);
            subscriber.error('Wait for websocket data timed out!');
          }
        }
      }, 10);
      return () => clearInterval(intervalId);
    });
  }

  private waitForWidgetsLoaded(timeout = 3000): Observable<void> {
    return new Observable<void>((subscriber) => {
      let elapsedMs = 0;
      const intervalId = setInterval(() => {
        if (!this.waitForWidgets.size && this.utils.currentPerfTime() - this.lastWaitWidgetTimeMs >= 100) {
          clearInterval(intervalId);
          subscriber.next();
          subscriber.complete();
        } else {
          elapsedMs += 10;
          if (elapsedMs >= timeout) {
            clearInterval(intervalId);
            subscriber.error('Wait for map tiles timed out!');
          }
        }
      }, 10);
      return () => clearInterval(intervalId);
    });
  }

  /**
   * DOM readiness check (PE-verbatim selectors): a top-level dashboard gridster is mounted
   * (not one nested inside a widget, e.g. a dashboard-state widget) and every widget's loading
   * overlay carries `!hidden` (see widget.component.html's existing
   * `[class.!hidden]="!loadingData"` binding on `div.tb-widget-loading` — no CE change needed,
   * the convention already matches PE's predicate).
   */
  private isReportPageDomReady(): boolean {
    const gridster = $('section.tb-dashboard-container gridster#gridster-child')
      .not('tb-widget-container gridster#gridster-child');
    if (gridster.length) {
      const loadingElements = $('tb-widget>div.tb-widget-loading').toArray();
      return loadingElements.length >= this.widgetsCount &&
        loadingElements.every(el => el.classList.contains('!hidden'));
    }
    return false;
  }

}
