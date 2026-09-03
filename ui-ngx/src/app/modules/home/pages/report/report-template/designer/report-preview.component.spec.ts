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

// Plain instantiation (no TestBed), matching this designer's established convention
// (report-canvas.component.spec.ts, report-page-settings.component.spec.ts): the refresh pipeline
// is plain RxJS (Subject + debounceTime) touching only the injected ReportService/DomSanitizer,
// never the DOM, so `fakeAsync`/`tick` alone is enough to drive the debounce without a component
// fixture.
import { fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { DomSanitizer } from '@angular/platform-browser';
import { ReportPreviewComponent } from './report-preview.component';
import { ReportService } from '@core/http/report.service';
import { serialize } from './report-configuration.serializer';
import { newPdfReportTemplateConfig } from '@shared/models/report-configuration.models';

describe('ReportPreviewComponent', () => {
  let reportService: jasmine.SpyObj<ReportService>;
  let sanitizer: jasmine.SpyObj<DomSanitizer>;

  beforeEach(() => {
    reportService = jasmine.createSpyObj('ReportService', ['previewReport']);
    sanitizer = jasmine.createSpyObj('DomSanitizer', ['bypassSecurityTrustResourceUrl']);
    spyOn(URL, 'createObjectURL').and.returnValue('blob:mock-url');
    spyOn(URL, 'revokeObjectURL');
  });

  function newComponent(): ReportPreviewComponent {
    const component = new ReportPreviewComponent(reportService, sanitizer);
    component.config = newPdfReportTemplateConfig();
    return component;
  }

  it('refresh() debounces, POSTs serialize(config) as reportTemplateConfig, and binds the sanitized object URL for a Blob result', fakeAsync(() => {
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' });
    reportService.previewReport.and.returnValue(of(blob));
    const component = newComponent();

    component.ngOnInit();
    // Nothing fires before the 300ms debounce elapses.
    expect(reportService.previewReport).not.toHaveBeenCalled();
    tick(300);

    expect(reportService.previewReport).toHaveBeenCalledTimes(1);
    expect(reportService.previewReport).toHaveBeenCalledWith({
      reportTemplateConfig: serialize(component.config),
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone
    });
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    expect(sanitizer.bypassSecurityTrustResourceUrl).toHaveBeenCalledWith('blob:mock-url');
    expect(component.previewUrl).toBe(sanitizer.bypassSecurityTrustResourceUrl.calls.mostRecent().returnValue);
    expect(component.loading).toBe(false);
    expect(component.failed).toBe(false);

    component.ngOnDestroy();
  }));

  it('coalesces rapid refresh() clicks into a single POST after the debounce window', fakeAsync(() => {
    reportService.previewReport.and.returnValue(of(new Blob(['a'], { type: 'application/pdf' })));
    const component = newComponent();
    component.ngOnInit();
    tick(300);
    reportService.previewReport.calls.reset();

    component.refresh();
    tick(100);
    component.refresh();
    tick(100);
    component.refresh();
    tick(300);

    expect(reportService.previewReport).toHaveBeenCalledTimes(1);

    component.ngOnDestroy();
  }));

  // Real timers, not fakeAsync: Blob.text() resolves on the browser's own microtask queue, which
  // fakeAsync's tick()/flushMicrotasks() do not drain. Waiting past the 300 ms debounce for real is
  // the only way to assert the decoded message without a flake.
  it('surfaces the server message decoded from the error blob, and sets no object URL', async () => {
    // The endpoint answers 400 BAD_REQUEST_PARAMS for every failure, the documented renderer-disabled
    // state included, so the status is not diagnostic - only the message is.
    const body = new Blob([JSON.stringify({status: 400, message: 'Report renderer is not enabled'})]);
    reportService.previewReport.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: body })));
    const component = newComponent();

    component.ngOnInit();
    await new Promise(resolve => setTimeout(resolve, 400));

    expect(component.failed).toBe(true);
    expect(component.errorMessage).toBe('Report renderer is not enabled');
    expect(component.previewUrl).toBeNull();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(component.loading).toBe(false);

    component.ngOnDestroy();
  });

  it('leaves the message null when the failure carries no decodable body', fakeAsync(() => {
    reportService.previewReport.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const component = newComponent();

    component.ngOnInit();
    tick(300);

    expect(component.failed).toBe(true);
    expect(component.errorMessage).toBeNull();
    expect(component.previewUrl).toBeNull();

    component.ngOnDestroy();
  }));

  it('revokes the previous object URL before creating the next one, and again on destroy', fakeAsync(() => {
    reportService.previewReport.and.returnValues(
      of(new Blob(['a'], { type: 'application/pdf' })),
      of(new Blob(['b'], { type: 'application/pdf' }))
    );
    (URL.createObjectURL as jasmine.Spy).and.returnValues('blob:first', 'blob:second');
    const component = newComponent();

    component.ngOnInit();
    tick(300);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();

    component.refresh();
    tick(300);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:first');
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1);

    component.ngOnDestroy();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:second');
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
  }));
});
