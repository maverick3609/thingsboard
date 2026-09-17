// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Title } from '@angular/platform-browser';
import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { filter } from 'rxjs/operators';

import { environment as env } from '@env/environment';
import { WhiteLabelingRuntimeService } from './white-labeling-runtime.service';

@Injectable({
  providedIn: 'root'
})
export class TitleService {
  constructor(
    private translate: TranslateService,
    private title: Title,
    private wlRuntime: WhiteLabelingRuntimeService
  ) {}

  setTitle(
    snapshot: ActivatedRouteSnapshot,
    lazyTranslate?: TranslateService
  ) {
    let lastChild = snapshot;
    while (lastChild.children.length) {
      lastChild = lastChild.children[0];
    }
    const { title } = lastChild.data;
    const translate = lazyTranslate || this.translate;
    const appTitle = this.wlRuntime.currentAppTitle || env.appTitle;
    if (title) {
      translate
        .get(title)
        .pipe(filter(translatedTitle => translatedTitle !== title))
        .subscribe(translatedTitle =>
          this.title.setTitle(`${appTitle} | ${translatedTitle}`)
        );
    } else {
      this.title.setTitle(appTitle);
    }
  }
}
