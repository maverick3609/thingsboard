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
import { AuthService } from '@core/auth/auth.service';
import { UntypedFormBuilder, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Constants } from '@shared/models/constants';
import { Router } from '@angular/router';
import { OAuth2ClientLoginInfo } from '@shared/models/oauth2.models';
import { validateEmail } from '@app/core/utils';
import { PageComponent } from '@shared/components/page.component';
import { finalize } from 'rxjs/operators';
import { WhiteLabelingHttpService } from '@core/http/white-labeling.service';
import { WhiteLabelingRuntimeService } from '@core/services/white-labeling-runtime.service';
import { LoginWhiteLabelingParams } from '@shared/models/white-labeling.models';

@Component({
    selector: 'tb-login',
    templateUrl: './login.component.html',
    styleUrls: ['./login.component.scss'],
    standalone: false
})
export class LoginComponent extends PageComponent implements OnInit {

  passwordViolation = false;
  isLoading = false;

  loginFormGroup = this.fb.group({
    username: ['', [Validators.required, validateEmail]],
    password: ['']
  });
  oauth2Clients: Array<OAuth2ClientLoginInfo> = null;

  loginLogo: string = 'assets/logo_title_white.svg';
  loginLogoHeight: number = null;
  loginCardColor: string = null;
  pageBackgroundColor: string = null;
  darkForeground: boolean = false;
  showNameVersion = false;
  platformName: string = null;
  platformVersion: string = null;
  showNameBottom = false;

  constructor(private authService: AuthService,
              public fb: UntypedFormBuilder,
              private router: Router,
              private wlHttp: WhiteLabelingHttpService,
              private wlRuntime: WhiteLabelingRuntimeService) {
    super();
  }

  ngOnInit() {
    this.oauth2Clients = this.authService.oauth2Clients;
    this.wlHttp.getLoginWhiteLabelParams({ ignoreErrors: true }).subscribe(
      (params: LoginWhiteLabelingParams) => {
        this.wlRuntime.applyLoginParams(params);
        if (params.logoImageUrl) { this.loginLogo = params.logoImageUrl; }
        if (params.logoImageHeight) { this.loginLogoHeight = params.logoImageHeight; }
        if (params.loginCardColor) { this.loginCardColor = params.loginCardColor; }
        if (params.pageBackgroundColor) { this.pageBackgroundColor = params.pageBackgroundColor; }
        this.darkForeground = params.darkForeground;
        // Login-scope "Platform info": the WL Login tab lets an admin show the platform
        // name/version on this page and choose the slot (under the logo / bottom of the
        // form). Nothing else renders it pre-auth — the platform-info slot added for the
        // post-auth app lives in home.component's side nav.
        this.showNameVersion = !!params.showNameVersion;
        this.platformName = params.platformName;
        this.platformVersion = params.platformVersion;
        this.showNameBottom = !!params.showNameBottom;
      },
      () => {}
    );
  }

  login(): void {
    if (this.loginFormGroup.valid) {
      this.isLoading = true;
      this.authService.login(this.loginFormGroup.value).pipe(
        finalize(() => {this.isLoading = false;})
      ).subscribe({
        error: (error: HttpErrorResponse) => {
          if (error && error.error && error.error.errorCode) {
            if (error.error.errorCode === Constants.serverErrorCode.credentialsExpired) {
              this.router.navigateByUrl(`login/resetExpiredPassword?resetToken=${error.error.resetToken}`);
            } else if (error.error.errorCode === Constants.serverErrorCode.passwordViolation) {
              this.passwordViolation = true;
            }
          }
        }
      });
    } else {
      this.loginFormGroup.markAllAsTouched();
    }
  }

  getOAuth2Uri(oauth2Client: OAuth2ClientLoginInfo): string {
    let result = "";
    if (this.authService.redirectUrl) {
      result += "?prevUri=" + encodeURIComponent(this.authService.redirectUrl);
    }
    return oauth2Client.url + result;
  }
}
