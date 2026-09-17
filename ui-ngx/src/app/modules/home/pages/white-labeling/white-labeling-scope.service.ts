// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';

/// Which scope the white-labeling tabs are editing. The General and Login tabs are
/// separate routes now, so the page component is destroyed and rebuilt on every tab
/// switch; without this a tenant admin editing a customer's branding would silently
/// drop back to tenant scope just by moving between tabs.
@Injectable({ providedIn: 'root' })
export class WhiteLabelingScopeService {
  isCustomerScope = false;
  customerId: string = null;

  reset(): void {
    this.isCustomerScope = false;
    this.customerId = null;
  }
}
