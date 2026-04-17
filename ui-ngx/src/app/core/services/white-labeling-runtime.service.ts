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
import { BehaviorSubject, Observable } from 'rxjs';
import { WhiteLabelingParams, LoginWhiteLabelingParams, PaletteSettings } from '@shared/models/white-labeling.models';

@Injectable({ providedIn: 'root' })
export class WhiteLabelingRuntimeService {

  private logoSubject = new BehaviorSubject<string>('assets/logo_title_white.svg');
  private appTitleSubject = new BehaviorSubject<string>(null);
  private helpLinkBaseUrlSubject = new BehaviorSubject<string>('https://thingsboard.io');
  private enableHelpLinksSubject = new BehaviorSubject<boolean>(true);
  private platformNameSubject = new BehaviorSubject<string>(null);
  private platformVersionSubject = new BehaviorSubject<string>(null);
  private showNameVersionSubject = new BehaviorSubject<boolean>(false);
  private hideConnectivityDialogSubject = new BehaviorSubject<boolean>(false);
  private paramsSubject = new BehaviorSubject<WhiteLabelingParams>(null);

  logo$: Observable<string> = this.logoSubject.asObservable();
  appTitle$: Observable<string> = this.appTitleSubject.asObservable();
  helpLinkBaseUrl$: Observable<string> = this.helpLinkBaseUrlSubject.asObservable();
  enableHelpLinks$: Observable<boolean> = this.enableHelpLinksSubject.asObservable();
  platformName$: Observable<string> = this.platformNameSubject.asObservable();
  platformVersion$: Observable<string> = this.platformVersionSubject.asObservable();
  showNameVersion$: Observable<boolean> = this.showNameVersionSubject.asObservable();
  hideConnectivityDialog$: Observable<boolean> = this.hideConnectivityDialogSubject.asObservable();
  params$: Observable<WhiteLabelingParams> = this.paramsSubject.asObservable();

  get currentLogo(): string {
    return this.logoSubject.value;
  }

  get currentAppTitle(): string {
    return this.appTitleSubject.value;
  }

  get currentHelpLinkBaseUrl(): string {
    return this.helpLinkBaseUrlSubject.value;
  }

  get currentEnableHelpLinks(): boolean {
    return this.enableHelpLinksSubject.value;
  }

  applyParams(params: WhiteLabelingParams): void {
    if (!params) return;
    this.paramsSubject.next(params);

    if (params.logoImageUrl) {
      this.logoSubject.next(params.logoImageUrl);
    }
    if (params.appTitle) {
      this.appTitleSubject.next(params.appTitle);
    }
    if (params.helpLinkBaseUrl != null) {
      this.helpLinkBaseUrlSubject.next(params.helpLinkBaseUrl);
    }
    if (params.enableHelpLinks != null) {
      this.enableHelpLinksSubject.next(params.enableHelpLinks);
    }
    if (params.platformName != null) {
      this.platformNameSubject.next(params.platformName);
    }
    if (params.platformVersion != null) {
      this.platformVersionSubject.next(params.platformVersion);
    }
    if (params.showNameVersion != null) {
      this.showNameVersionSubject.next(params.showNameVersion);
    }
    if (params.hideConnectivityDialog != null) {
      this.hideConnectivityDialogSubject.next(params.hideConnectivityDialog);
    }

    this.applyFavicon(params.favicon?.url);
    this.applyCustomCss(params.customCss);
    this.applyPalette(params.paletteSettings);
  }

  applyLoginParams(params: LoginWhiteLabelingParams): void {
    if (!params) return;
    this.applyParams(params);
  }

  reset(): void {
    this.logoSubject.next('assets/logo_title_white.svg');
    this.appTitleSubject.next(null);
    this.helpLinkBaseUrlSubject.next('https://thingsboard.io');
    this.enableHelpLinksSubject.next(true);
    this.platformNameSubject.next(null);
    this.platformVersionSubject.next(null);
    this.showNameVersionSubject.next(false);
    this.hideConnectivityDialogSubject.next(false);
    this.paramsSubject.next(null);
    this.removeFavicon();
    this.removeCustomCss();
    this.removePalette();
  }

  private applyFavicon(url: string): void {
    if (!url) return;
    let link: HTMLLinkElement = document.querySelector('link[rel="icon"]');
    if (!link) {
      link = document.createElement('link');
      link.rel = 'icon';
      document.head.appendChild(link);
    }
    link.href = url;
  }

  private removeFavicon(): void {
    const link: HTMLLinkElement = document.querySelector('link[rel="icon"]');
    if (link) {
      link.href = 'thingsboard.ico';
    }
  }

  private applyCustomCss(css: string): void {
    let styleEl = document.getElementById('wl-custom-css');
    if (!css) {
      if (styleEl) styleEl.remove();
      return;
    }
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'wl-custom-css';
      document.head.appendChild(styleEl);
    }
    styleEl.textContent = css;
  }

  private removeCustomCss(): void {
    const styleEl = document.getElementById('wl-custom-css');
    if (styleEl) styleEl.remove();
  }

  private applyPalette(paletteSettings: PaletteSettings): void {
    if (!paletteSettings) return;
    let styleEl = document.getElementById('wl-palette');
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = 'wl-palette';
      document.head.appendChild(styleEl);
    }

    let css = ':root {\n';
    if (paletteSettings.primaryPalette?.colors) {
      for (const [shade, color] of Object.entries(paletteSettings.primaryPalette.colors)) {
        css += `  --tb-primary-${shade}: ${color};\n`;
      }
      if (paletteSettings.primaryPalette.colors['500']) {
        css += `  --tb-primary-color: ${paletteSettings.primaryPalette.colors['500']};\n`;
      }
    }
    if (paletteSettings.accentPalette?.colors) {
      for (const [shade, color] of Object.entries(paletteSettings.accentPalette.colors)) {
        css += `  --tb-accent-${shade}: ${color};\n`;
      }
    }
    css += '}\n';
    styleEl.textContent = css;
  }

  private removePalette(): void {
    const styleEl = document.getElementById('wl-palette');
    if (styleEl) styleEl.remove();
  }
}
