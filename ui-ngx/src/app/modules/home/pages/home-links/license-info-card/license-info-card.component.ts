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

import {
  booleanAttribute,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  Input,
  OnInit
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslateModule } from '@ngx-translate/core';
import { ClipboardService } from 'ngx-clipboard';
import { catchError, of } from 'rxjs';
import { LicenseService } from '@core/http/license.service';
import {
  LICENSE_CRITICAL_DAYS,
  LICENSE_WARN_DAYS,
  LICENSE_WARN_PERCENT,
  LicenseInfo,
  LicenseSeverity
} from '@shared/models/license.models';

/**
 * Licence summary on /home. Standalone and dependency-light on purpose: LicenseService and ngx-clipboard's
 * ClipboardService are its only injected dependencies, and both are {@code providedIn: 'root'} -- so
 * neither needs a SharedModule import, which keeps this component clear of the DialogService <->
 * EntityLimitExceededDialogComponent circular import that breaks any Karma bundle pulling in the platform
 * ImportExportService.
 */
@Component({
  selector: 'tb-license-info-card',
  templateUrl: './license-info-card.component.html',
  styleUrls: ['./license-info-card.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, MatCardModule, MatButtonModule, MatIconModule,
            MatTooltipModule, MatProgressBarModule, TranslateModule]
})
export class LicenseInfoCardComponent implements OnInit {

  /**
   * Drops the mat-card chrome so the same rows can be hosted by a home-dashboard widget, whose frame
   * already draws the card. Set from the widget type's templateHtml; the /home fallback grid leaves it
   * false and keeps the card.
   */
  // booleanAttribute so the bare attribute form works: the widget type's templateHtml is
  // `<tb-license-info-card embedded>`, which hands the input the empty string, and without the
  // transform that is falsy and the widget silently falls back to the standalone card.
  @Input({transform: booleanAttribute}) embedded = false;

  license: LicenseInfo = null;
  copied = false;

  constructor(private licenseService: LicenseService,
              private clipboardService: ClipboardService,
              private cd: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.licenseService.getLicenseInfo({ ignoreErrors: true }).pipe(
      catchError(() => of(null))
    ).subscribe((license) => {
      this.license = license ?? null;
      this.cd.markForCheck();
    });
  }

  isUnlimited(cap: number | null): boolean {
    return cap === null || cap === undefined;
  }

  /** null when the cap is unlimited, so the template knows to skip the bar. */
  percent(used: number, cap: number | null): number | null {
    if (this.isUnlimited(cap)) {
      return null;
    }
    if (cap === 0) {
      return 100; // zero-cap licences are at capacity from the first entity per DefaultLicenseService.checkCreateAllowed
    }
    return Math.min(100, Math.round((used / cap) * 100));
  }

  get severity(): LicenseSeverity {
    if (!this.license) {
      return 'ok';
    }
    const worst = Math.max(
      this.percent(this.license.devices, this.license.maxDevices) ?? 0,
      this.percent(this.license.assets, this.license.maxAssets) ?? 0
    );
    if (this.license.daysRemaining < LICENSE_CRITICAL_DAYS || worst >= 100) {
      return 'critical';
    }
    if (this.license.daysRemaining < LICENSE_WARN_DAYS || worst >= LICENSE_WARN_PERCENT) {
      return 'warn';
    }
    return 'ok';
  }

  get expiryDate(): Date {
    return new Date(this.license.expiresAt * 1000);
  }

  copyInstanceId(): void {
    // ClipboardService.copy uses document.execCommand('copy'), unlike navigator.clipboard.writeText,
    // so it also works on a plain http:// deployment where navigator.clipboard is undefined.
    this.clipboardService.copy(this.license.instanceId);
    this.copied = true;
    this.cd.markForCheck();
  }
}
