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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { ClipboardService } from 'ngx-clipboard';
import { LicenseInfoCardComponent } from './license-info-card.component';
import { LicenseInfo } from '@shared/models/license.models';

describe('LicenseInfoCardComponent', () => {

  let fixture: ComponentFixture<LicenseInfoCardComponent>;
  let component: LicenseInfoCardComponent;
  let httpMock: HttpTestingController;

  const info = (over: Partial<LicenseInfo> = {}): LicenseInfo => ({
    customer: 'Acme Pvt Ltd',
    instanceId: '9f3a1c02-4b6d-4c3e-9a10-2f7c5d3e8b71',
    expiresAt: 1795033600,
    daysRemaining: 211,
    devices: 3104,
    maxDevices: 5000,
    assets: 412,
    maxAssets: 2000,
    ...over
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      // TranslateModule.forRoot() with no loader config defaults to TranslateNoOpLoader (of({}), no HTTP
      // call) -- the component's template leans on the `translate` pipe (title/labels/tooltips), which
      // needs a TranslateService in the injector or every render throws NG0201. Untranslated keys render
      // as their raw key string, which is fine: no spec here asserts on translated label text.
      imports: [HttpClientTestingModule, TranslateModule.forRoot(), LicenseInfoCardComponent],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LicenseInfoCardComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => httpMock.verify());

  function load(payload: LicenseInfo | null, status?: number) {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/license/info');
    if (status) {
      req.flush(null, { status, statusText: 'Forbidden' });
    } else {
      req.flush(payload);
    }
    fixture.detectChanges();
  }

  it('renders the licence once loaded', () => {
    load(info());
    expect(component.license).toBeTruthy();
    expect(component.license.customer).toBe('Acme Pvt Ltd');
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Acme Pvt Ltd');
    expect(text).toContain('3,104');
    expect(text).toContain('5,000');
  });

  it('computes percentages', () => {
    load(info({ devices: 2500, maxDevices: 5000 }));
    expect(component.percent(2500, 5000)).toBe(50);
  });

  it('treats a null cap as unlimited', () => {
    load(info({ maxDevices: null }));
    expect(component.percent(3104, null)).toBeNull();
    expect(component.isUnlimited(null)).toBeTrue();
  });

  it('renders no progress bar and an "unlimited" count for a null cap', () => {
    load(info({ maxDevices: null }));
    const bars = fixture.nativeElement.querySelectorAll('mat-progress-bar');
    // Only the assets row (still capped at 2000) should have drawn a bar; the
    // devices row, with a null cap, must draw none.
    expect(bars.length).toBe(1);
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('3,104');
    expect(text).not.toContain('/ null');
  });

  it('treats a zero cap as already exhausted, not unlimited', () => {
    load(info({ devices: 1, maxDevices: 0, daysRemaining: 400 }));
    expect(component.percent(1, 0)).toBe(100);
    expect(component.isUnlimited(0)).toBeFalse();
    expect(component.severity).toBe('critical');
  });

  it('draws a full progress bar for a zero cap instead of hiding it as unlimited', () => {
    load(info({ devices: 1, maxDevices: 0, daysRemaining: 400 }));
    const bars = fixture.nativeElement.querySelectorAll('mat-progress-bar');
    // A zero cap is a real, already-exhausted cap -- not unlimited -- so both
    // the devices row and the assets row (still capped at 2000) draw a bar.
    expect(bars.length).toBe(2);
  });

  it('is amber below thirty days remaining', () => {
    load(info({ daysRemaining: 29 }));
    expect(component.severity).toBe('warn');
  });

  it('is ok at exactly thirty days remaining (boundary is exclusive)', () => {
    load(info({ daysRemaining: 30 }));
    expect(component.severity).toBe('ok');
  });

  it('is amber at ninety percent capacity', () => {
    load(info({ daysRemaining: 400, devices: 4500, maxDevices: 5000 }));
    expect(component.severity).toBe('warn');
  });

  it('is not warn at just under ninety percent capacity', () => {
    load(info({ daysRemaining: 400, devices: 4450, maxDevices: 5000 }));
    expect(component.severity).toBe('ok');
  });

  it('is red below seven days remaining', () => {
    load(info({ daysRemaining: 6 }));
    expect(component.severity).toBe('critical');
  });

  it('is amber, not red, at exactly seven days remaining (boundary is exclusive)', () => {
    load(info({ daysRemaining: 7 }));
    expect(component.severity).toBe('warn');
  });

  it('is red at full capacity', () => {
    load(info({ daysRemaining: 400, devices: 5000, maxDevices: 5000 }));
    expect(component.severity).toBe('critical');
  });

  it('is neutral when healthy', () => {
    load(info({ daysRemaining: 211 }));
    expect(component.severity).toBe('ok');
  });

  it('is red when assets alone are at full capacity, devices and days healthy', () => {
    load(info({ daysRemaining: 400, devices: 100, maxDevices: 5000, assets: 2000, maxAssets: 2000 }));
    expect(component.severity).toBe('critical');
  });

  it('copies the instance id and flips the icon', () => {
    load(info());
    // ClipboardService.copy is synchronous (document.execCommand('copy')), unlike the
    // navigator.clipboard.writeText Promise this used to spy on -- works on insecure origins too.
    const clipboardService = TestBed.inject(ClipboardService);
    spyOn(clipboardService, 'copy');
    fixture.nativeElement.querySelector('button[mat-icon-button]').click();
    expect(clipboardService.copy).toHaveBeenCalledWith('9f3a1c02-4b6d-4c3e-9a10-2f7c5d3e8b71');
    expect(component.copied).toBeTrue();
  });

  it('hides itself on a 403', () => {
    load(null, 403);
    expect(component.license).toBeNull();
    expect(fixture.nativeElement.querySelector('mat-card')).toBeNull();
  });

  it('hides itself when the body is empty', () => {
    load(null);
    expect(component.license).toBeNull();
    expect(fixture.nativeElement.querySelector('mat-card')).toBeNull();
  });

  it('hides the customer line and the instance row when those fields are absent', () => {
    load(info({ customer: null, instanceId: null }));

    expect(fixture.nativeElement.querySelector('.tb-license-customer')).toBeNull();
    expect(fixture.nativeElement.querySelector('.tb-license-instance')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('mat-progress-bar').length).toBe(2);
  });
});
