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

// Full TestBed render - a first for this designer subtree's specs (report-canvas/report-preview/
// report-page-settings/etc. all deliberately avoid TestBed, see their own header comments, because
// their logic never touches the DOM). This one has to: what's under test IS the DOM - which
// <mat-tab> labels actually render for a given `tabs` input. A minimal, self-contained
// TranslateModule config (a stub loader resolving only the 3 keys under test, plus ngx-translate's
// own library-native TranslateNoOpCompiler/TranslateDefaultParser/DefaultMissingTranslationHandler)
// stands in for the app's real TranslateDefaultLoader (core.module.ts), which does a live HTTP
// fetch of the ~1500-key locale JSON - unnecessary weight, and an HttpClientTestingModule
// dependency, for a unit test that only cares whether 2-3 of those keys resolve.
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatTabsModule } from '@angular/material/tabs';
import { of } from 'rxjs';
import {
  DefaultMissingTranslationHandler,
  MissingTranslationHandler,
  TranslateCompiler,
  TranslateDefaultParser,
  TranslateLoader,
  TranslateModule,
  TranslateNoOpCompiler,
  TranslateParser
} from '@ngx-translate/core';
import { ReportConfigTabsComponent } from './report-config-tabs.component';

const stubTranslations = {
  report: {
    designer: {
      'tab-content': 'Content',
      'tab-data': 'Data',
      'tab-layout': 'Layout'
    }
  }
};

// Host projects only [tabContent] + [tabLayout] - no [tabData] - so the Data tab must not render.
@Component({
  template: `
    <tb-report-config-tabs [tabs]="['content', 'layout']">
      <div tabContent>content body</div>
      <div tabLayout>layout body</div>
    </tb-report-config-tabs>
  `,
  standalone: false
})
class ContentAndLayoutHostComponent {
}

// Host projects all three slots, in Content/Data/Layout order, proving the 'data' branch (which
// ContentAndLayoutHostComponent above never exercises) also renders, and in the right order.
@Component({
  template: `
    <tb-report-config-tabs [tabs]="['content', 'data', 'layout']">
      <div tabContent>content body</div>
      <div tabData>data body</div>
      <div tabLayout>layout body</div>
    </tb-report-config-tabs>
  `,
  standalone: false
})
class AllTabsHostComponent {
}

describe('ReportConfigTabsComponent', () => {

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ReportConfigTabsComponent, ContentAndLayoutHostComponent, AllTabsHostComponent],
      imports: [
        CommonModule,
        MatTabsModule,
        TranslateModule.forRoot({
          lang: 'en_US',
          loader: { provide: TranslateLoader, useValue: { getTranslation: () => of(stubTranslations) } },
          compiler: { provide: TranslateCompiler, useClass: TranslateNoOpCompiler },
          parser: { provide: TranslateParser, useClass: TranslateDefaultParser },
          missingTranslationHandler: { provide: MissingTranslationHandler, useClass: DefaultMissingTranslationHandler }
        })
      ],
      providers: [provideNoopAnimations()]
    }).compileComponents();
  });

  function tabLabels(fixture: { nativeElement: any }): string[] {
    const labels = fixture.nativeElement.querySelectorAll('.mat-mdc-tab .mdc-tab__text-label');
    return Array.from(labels).map((l: any) => l.textContent.trim());
  }

  it('renders only the tabs whose slots are provided', () => {
    const fixture = TestBed.createComponent(ContentAndLayoutHostComponent); // host projects only [tabContent]+[tabLayout]
    fixture.detectChanges();

    expect(tabLabels(fixture)).toEqual(['Content', 'Layout']);
  });

  it('renders all three tabs, in Content/Data/Layout order, when every slot is provided', () => {
    const fixture = TestBed.createComponent(AllTabsHostComponent);
    fixture.detectChanges();

    expect(tabLabels(fixture)).toEqual(['Content', 'Data', 'Layout']);
  });
});
