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

import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { WhiteLabelingHttpService } from '@core/http/white-labeling.service';
import { WhiteLabelingRuntimeService } from '@core/services/white-labeling-runtime.service';
import { LoginWhiteLabelingParams, WhiteLabelingParams } from '@shared/models/white-labeling.models';
import { EntityType } from '@shared/models/entity-type.models';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { WhiteLabelingScopeService } from './white-labeling-scope.service';

/// One route per tab, so RouterTabsComponent can render the tab bar at the top of the
/// page like every other tabbed section of the platform. This component backs all five
/// routes and renders the section named by the route's `wlTab` data.
export type WhiteLabelingTab = 'general' | 'login' | 'mail-templates' | 'privacy-policy' | 'terms-of-use';

@Component({
  standalone: false,
  selector: 'tb-white-labeling',
  templateUrl: './white-labeling.component.html',
  styleUrls: ['./white-labeling.component.scss', '../admin/settings-card.scss']
})
export class WhiteLabelingComponent implements OnInit {
  generalParams: WhiteLabelingParams = null;
  loginParams: LoginWhiteLabelingParams = null;
  privacyPolicy: any = null;
  termsOfUse: any = null;
  authority: Authority;
  loading = false;
  tab: WhiteLabelingTab = 'general';
  generalDirty = false;
  loginDirty = false;

  readonly entityType = EntityType;

  constructor(
    private store: Store<AppState>,
    private wlHttp: WhiteLabelingHttpService,
    private wlRuntime: WhiteLabelingRuntimeService,
    private snackBar: MatSnackBar,
    private dialogService: DialogService,
    private translate: TranslateService,
    private route: ActivatedRoute,
    private scope: WhiteLabelingScopeService
  ) {}

  get isCustomerScope(): boolean {
    return this.scope.isCustomerScope;
  }

  get selectedCustomerId(): string {
    return this.scope.customerId;
  }

  set selectedCustomerId(customerId: string) {
    this.scope.customerId = customerId;
  }

  ngOnInit(): void {
    const authUser = getCurrentAuthUser(this.store);
    this.authority = authUser?.authority;
    this.route.data.subscribe(data => {
      this.tab = data.wlTab ?? 'general';
      this.loadParams();
    });
  }

  // Each tab is its own route, so only fetch what the visible tab renders.
  loadParams(): void {
    const customerId = this.customerIdParam();
    switch (this.tab) {
      case 'general':
        this.loading = true;
        this.wlHttp.getCurrentWhiteLabelParams(customerId, { ignoreErrors: true }).subscribe({
          next: params => {
            this.generalParams = params || {} as WhiteLabelingParams;
            this.generalDirty = false;
            this.loading = false;
          },
          error: () => {
            this.generalParams = {} as WhiteLabelingParams;
            this.loading = false;
          }
        });
        break;
      case 'login':
        this.loading = true;
        this.wlHttp.getCurrentLoginWhiteLabelParams(customerId, { ignoreErrors: true }).subscribe({
          next: params => {
            this.loginParams = params || {} as LoginWhiteLabelingParams;
            this.loginDirty = false;
            this.loading = false;
          },
          error: () => {
            this.loginParams = {} as LoginWhiteLabelingParams;
            this.loading = false;
          }
        });
        break;
      case 'privacy-policy':
        this.wlHttp.getPrivacyPolicy({ ignoreErrors: true }).subscribe({
          next: data => this.privacyPolicy = data || { content: '' },
          error: () => this.privacyPolicy = { content: '' }
        });
        break;
      case 'terms-of-use':
        this.wlHttp.getTermsOfUse({ ignoreErrors: true }).subscribe({
          next: data => this.termsOfUse = data || { content: '' },
          error: () => this.termsOfUse = { content: '' }
        });
        break;
    }
  }

  saveGeneral(): void {
    this.loading = true;
    this.wlHttp.saveWhiteLabelParams(this.generalParams, this.customerIdParam()).subscribe({
      next: saved => {
        this.generalParams = saved;
        this.generalDirty = false;
        this.wlRuntime.applyParams(saved);
        this.loading = false;
        this.showMessage('white-labeling.settings-saved');
      },
      error: () => { this.loading = false; }
    });
  }

  saveLogin(): void {
    this.loading = true;
    this.wlHttp.saveLoginWhiteLabelParams(this.loginParams, this.customerIdParam()).subscribe({
      next: saved => {
        this.loginParams = saved;
        this.loginDirty = false;
        this.loading = false;
        this.showMessage('white-labeling.settings-saved');
      },
      error: () => { this.loading = false; }
    });
  }

  resetSettings(scope: 'GENERAL' | 'LOGIN'): void {
    const titleKey = scope === 'LOGIN' ? 'white-labeling.reset-login-white-label-title'
                                       : 'white-labeling.reset-white-label-title';
    const textKey = scope === 'LOGIN' ? 'white-labeling.reset-login-white-label-text'
                                      : 'white-labeling.reset-white-label-text';
    this.dialogService.confirm(
      this.translate.instant(titleKey),
      this.translate.instant(textKey),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(result => {
      if (result) {
        this.doReset(scope);
      }
    });
  }

  private doReset(scope: 'GENERAL' | 'LOGIN'): void {
    this.loading = true;
    const customerId = this.customerIdParam();
    const request = scope === 'LOGIN' ? this.wlHttp.deleteCurrentLoginWhiteLabelParams(customerId)
                                      : this.wlHttp.deleteCurrentWhiteLabelParams(customerId);
    request.subscribe({
      next: () => {
        this.showMessage('white-labeling.settings-deleted');
        if (scope === 'GENERAL') {
          this.wlRuntime.reset();
        }
        this.loadParams();
      },
      error: () => { this.loading = false; }
    });
  }

  preview(): void {
    this.wlHttp.previewWhiteLabelParams(this.generalParams).subscribe({
      next: merged => {
        this.wlRuntime.applyParams(merged);
        this.showMessage('white-labeling.preview-applied');
      }
    });
  }

  onGeneralParamsChange(params: WhiteLabelingParams): void {
    this.generalParams = params;
    this.generalDirty = true;
  }

  onLoginParamsChange(params: LoginWhiteLabelingParams): void {
    this.loginParams = params;
    this.loginDirty = true;
  }

  get isTenantAdmin(): boolean {
    return this.authority === Authority.TENANT_ADMIN;
  }

  get isSysAdmin(): boolean {
    return this.authority === Authority.SYS_ADMIN;
  }

  get canEditLegal(): boolean {
    return this.authority === Authority.SYS_ADMIN || this.authority === Authority.TENANT_ADMIN;
  }

  savePrivacyPolicy(): void {
    this.wlHttp.savePrivacyPolicy(this.privacyPolicy).subscribe({
      next: saved => {
        this.privacyPolicy = saved;
        this.showMessage('white-labeling.privacy-policy-saved');
      }
    });
  }

  saveTermsOfUse(): void {
    this.wlHttp.saveTermsOfUse(this.termsOfUse).subscribe({
      next: saved => {
        this.termsOfUse = saved;
        this.showMessage('white-labeling.terms-of-use-saved');
      }
    });
  }

  private customerIdParam(): string | undefined {
    return this.isCustomerScope ? this.selectedCustomerId : undefined;
  }

  private showMessage(key: string): void {
    this.snackBar.open(this.translate.instant(key), this.translate.instant('action.ok'), { duration: 3000 });
  }

  toggleCustomerScope(): void {
    this.scope.isCustomerScope = !this.scope.isCustomerScope;
    if (!this.scope.isCustomerScope) {
      this.scope.customerId = null;
    }
    this.loadParams();
  }

  // The scope selector only makes sense where there is a scope to choose between.
  get showScopeSelector(): boolean {
    return this.isTenantAdmin && (this.tab === 'general' || this.tab === 'login');
  }

  // The card is titled after the tab, matching the platform's other tabbed sections
  // where each tab is a page in its own right; the breadcrumb carries "White labeling".
  get titleKey(): string {
    switch (this.tab) {
      case 'login':
        return 'white-labeling.login-page';
      case 'mail-templates':
        return 'white-labeling.mail-templates';
      case 'privacy-policy':
        return 'white-labeling.privacy-policy';
      case 'terms-of-use':
        return 'white-labeling.terms-of-use';
      default:
        return 'white-labeling.general';
    }
  }
}
