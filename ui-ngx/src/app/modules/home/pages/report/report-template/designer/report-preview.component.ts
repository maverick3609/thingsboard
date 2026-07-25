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
  // null = no error (either a preview is showing, or none has been requested/resolved yet).
  // Distinguishes the renderer-disabled 400 (an expected, documented state) from any other failure.
  errorState: 'renderer-off' | 'other' | null = null;

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
            this.errorState = err.status === 400 ? 'renderer-off' : 'other';
            this.clearPreview();
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
    this.errorState = null;
    this.refresh$.next();
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
