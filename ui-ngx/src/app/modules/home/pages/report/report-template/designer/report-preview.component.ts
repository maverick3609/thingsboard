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

import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { EMPTY, Subject } from 'rxjs';
import { catchError, debounceTime, switchMap, takeUntil } from 'rxjs/operators';
import { ReportService } from '@core/http/report.service';
import { ReportTemplateConfigModel } from '@shared/models/report-configuration.models';
import { serialize } from '@home/pages/report/report-template/designer/report-configuration.serializer';

// Whole-template PDF preview - the shell's viewMode === 'preview' pane (report-template-editor
// .component.html), a sibling of the edit body rather than nested inside it (T7). Serializes the
// *current in-memory* config fresh on every refresh (T3's config Input; never persisted first) and
// posts it to the existing R1/R2a renderer-gated test endpoint (ReportController#testReport via
// ReportService#previewReport) - works for an unsaved "new" template the same as a saved one, since
// no reportTemplateId is involved. Manual Refresh only: auto-refreshing on every canvas/config edit
// would hammer the render endpoint, so a Subject<void> + debounceTime just coalesces rapid clicks;
// one refresh is also fired on init so the pane isn't blank the moment you switch into it.
@Component({
  selector: 'tb-report-preview',
  templateUrl: './report-preview.component.html',
  styleUrls: ['./report-preview.component.scss'],
  standalone: false
})
export class ReportPreviewComponent implements OnInit, OnDestroy {

  @Input()
  config: ReportTemplateConfigModel;

  previewUrl: SafeResourceUrl | null = null;
  loading = false;
  // True once a refresh has failed, cleared by the next one. `errorMessage` carries the server's own
  // explanation when it could be decoded; null means the template shows a generic string instead.
  failed = false;
  errorMessage: string | null = null;

  private refresh$ = new Subject<void>();
  private destroy$ = new Subject<void>();
  // The raw blob: URL backing `previewUrl` - SafeResourceUrl wraps it opaquely, so this is the only
  // handle URL.revokeObjectURL has. Revoked before every new preview and again on destroy so this
  // pane never leaks one blob: URL per refresh.
  private objectUrl: string | null = null;

  constructor(private reportService: ReportService,
              private sanitizer: DomSanitizer) {
  }

  ngOnInit(): void {
    this.refresh$.pipe(
      debounceTime(300),
      switchMap(() => {
        const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        return this.reportService.previewReport({reportTemplateConfig: serialize(this.config), timezone}).pipe(
          catchError((err: HttpErrorResponse) => {
            this.loading = false;
            this.failed = true;
            this.clearPreview();
            this.readErrorMessage(err);
            return EMPTY;
          })
        );
      }),
      takeUntil(this.destroy$)
    ).subscribe((blob: Blob) => {
      this.loading = false;
      this.setPreview(blob);
    });
    this.refresh();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.revokeObjectUrl();
  }

  refresh(): void {
    this.loading = true;
    this.failed = false;
    this.errorMessage = null;
    this.refresh$.next();
  }

  // Every failure this endpoint can produce arrives as 400 BAD_REQUEST_PARAMS - the documented
  // renderer-disabled state and a genuine render failure alike - so the status code cannot tell them
  // apart and only the server's message can. Because the request sets responseType 'blob', that
  // message arrives as an undecoded Blob; if decoding it fails the template falls back to a generic
  // string rather than asserting a cause we do not know.
  private readErrorMessage(err: HttpErrorResponse): void {
    if (!(err.error instanceof Blob)) {
      return;
    }
    err.error.text().then(text => {
      try {
        this.errorMessage = JSON.parse(text).message ?? null;
      } catch {
        this.errorMessage = null;
      }
    }, () => {});
  }

  private setPreview(blob: Blob): void {
    this.revokeObjectUrl();
    this.objectUrl = URL.createObjectURL(blob);
    this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.objectUrl);
  }

  private clearPreview(): void {
    this.revokeObjectUrl();
    this.previewUrl = null;
  }

  private revokeObjectUrl(): void {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }
}
