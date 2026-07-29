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

import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { LoginWhiteLabelingParams } from '@shared/models/white-labeling.models';
import { DomainId } from '@shared/models/id/domain-id';
import { DomainInfo } from '@shared/models/oauth2.models';
import { DomainService } from '@core/http/domain.service';
import { PageLink } from '@shared/models/page/page-link';
import { WEB_URL_REGEX } from '@shared/models/mobile-app.models';

@Component({
  standalone: false,
  selector: 'tb-login-wl-settings',
  templateUrl: './login-wl-settings.component.html'
})
export class LoginWlSettingsComponent implements OnInit, OnChanges {
  @Input() params: LoginWhiteLabelingParams;
  @Output() paramsChange = new EventEmitter<LoginWhiteLabelingParams>();

  form: FormGroup;

  // Populated best-effort: listing Domain entities is a SYS_ADMIN-only endpoint
  // (see DomainController#getDomainInfos), so this stays empty for TENANT_ADMIN /
  // CUSTOMER_USER sessions and the select simply offers the "none" option.
  domains: DomainInfo[] = [];

  readonly webUrlPattern = WEB_URL_REGEX;

  constructor(private fb: FormBuilder, private domainService: DomainService) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      logoImageUrl: [null],
      logoImageHeight: [null],
      loginCardColor: [null],
      pageBackgroundColor: [null],
      darkForeground: [false],
      showNameBottom: [false],
      domainId: [null],
      baseUrl: [null, [Validators.pattern(this.webUrlPattern)]]
    });
    this.form.valueChanges.subscribe(() => this.emitParams());
    this.patchForm();
    this.loadDomains();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.params && this.form) {
      this.patchForm();
    }
  }

  compareDomainIds(a: DomainId, b: DomainId): boolean {
    return a?.id === b?.id;
  }

  private loadDomains(): void {
    this.domainService.getTenantDomainInfos(new PageLink(100), { ignoreErrors: true }).subscribe({
      next: page => { this.domains = page.data; },
      error: () => { this.domains = []; }
    });
  }

  private patchForm(): void {
    if (!this.params) { return; }
    this.form.patchValue({
      logoImageUrl: this.params.logoImageUrl || null,
      logoImageHeight: this.params.logoImageHeight || null,
      loginCardColor: this.params.loginCardColor || null,
      pageBackgroundColor: this.params.pageBackgroundColor || null,
      darkForeground: this.params.darkForeground ?? false,
      showNameBottom: this.params.showNameBottom ?? false,
      domainId: this.params.domainId || null,
      baseUrl: this.params.baseUrl || null
    }, { emitEvent: false });
  }

  private emitParams(): void {
    const v = this.form.value;
    this.paramsChange.emit({
      ...this.params,
      logoImageUrl: v.logoImageUrl,
      logoImageHeight: v.logoImageHeight,
      loginCardColor: v.loginCardColor,
      pageBackgroundColor: v.pageBackgroundColor,
      darkForeground: v.darkForeground,
      showNameBottom: v.showNameBottom,
      domainId: v.domainId,
      baseUrl: v.baseUrl
    });
  }
}
