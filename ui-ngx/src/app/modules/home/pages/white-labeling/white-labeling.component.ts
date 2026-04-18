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
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { WhiteLabelingHttpService } from '@core/http/white-labeling.service';
import { WhiteLabelingRuntimeService } from '@core/services/white-labeling-runtime.service';
import { LoginWhiteLabelingParams, WhiteLabelingParams } from '@shared/models/white-labeling.models';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  standalone: false,
  selector: 'tb-white-labeling',
  templateUrl: './white-labeling.component.html',
  styleUrls: ['./white-labeling.component.scss']
})
export class WhiteLabelingComponent implements OnInit {
  generalParams: WhiteLabelingParams = null;
  loginParams: LoginWhiteLabelingParams = null;
  authority: Authority;
  loading = false;
  selectedCustomerId: string = null;
  isCustomerScope = false;

  constructor(
    private store: Store<AppState>,
    private wlHttp: WhiteLabelingHttpService,
    private wlRuntime: WhiteLabelingRuntimeService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    const authUser = getCurrentAuthUser(this.store);
    this.authority = authUser?.authority;
    this.loadParams();
  }

  loadParams(): void {
    this.loading = true;
    const customerId = this.isCustomerScope ? this.selectedCustomerId : undefined;
    this.wlHttp.getCurrentWhiteLabelParams(customerId, { ignoreErrors: true }).subscribe({
      next: params => {
        this.generalParams = params || {} as WhiteLabelingParams;
        this.loading = false;
      },
      error: () => {
        this.generalParams = {} as WhiteLabelingParams;
        this.loading = false;
      }
    });
    this.wlHttp.getCurrentLoginWhiteLabelParams(customerId, { ignoreErrors: true }).subscribe({
      next: params => {
        this.loginParams = params || {} as LoginWhiteLabelingParams;
      },
      error: () => {
        this.loginParams = {} as LoginWhiteLabelingParams;
      }
    });
  }

  save(): void {
    this.loading = true;
    const customerId = this.isCustomerScope ? this.selectedCustomerId : undefined;
    this.wlHttp.saveWhiteLabelParams(this.generalParams, customerId).subscribe({
      next: saved => {
        this.generalParams = saved;
        this.wlRuntime.applyParams(saved);
        this.wlHttp.saveLoginWhiteLabelParams(this.loginParams, customerId).subscribe({
          next: savedLogin => {
            this.loginParams = savedLogin;
            this.loading = false;
            this.snackBar.open('White labeling settings saved', 'OK', { duration: 3000 });
          },
          error: () => { this.loading = false; }
        });
      },
      error: () => { this.loading = false; }
    });
  }

  deleteSettings(): void {
    this.loading = true;
    const customerId = this.isCustomerScope ? this.selectedCustomerId : undefined;
    this.wlHttp.deleteCurrentWhiteLabelParams(customerId).subscribe({
      next: () => {
        this.wlHttp.deleteCurrentLoginWhiteLabelParams(customerId).subscribe({
          next: () => {
            this.snackBar.open('White labeling settings deleted', 'OK', { duration: 3000 });
            this.wlRuntime.reset();
            this.loadParams();
          },
          error: () => { this.loading = false; }
        });
      },
      error: () => { this.loading = false; }
    });
  }

  preview(): void {
    this.wlHttp.previewWhiteLabelParams(this.generalParams).subscribe({
      next: merged => {
        this.wlRuntime.applyParams(merged);
        this.snackBar.open('Preview applied', 'OK', { duration: 2000 });
      }
    });
  }

  onGeneralParamsChange(params: WhiteLabelingParams): void {
    this.generalParams = params;
  }

  onLoginParamsChange(params: LoginWhiteLabelingParams): void {
    this.loginParams = params;
  }

  get isTenantAdmin(): boolean {
    return this.authority === Authority.TENANT_ADMIN;
  }

  toggleCustomerScope(): void {
    this.isCustomerScope = !this.isCustomerScope;
    if (!this.isCustomerScope) {
      this.selectedCustomerId = null;
    }
    this.loadParams();
  }
}
