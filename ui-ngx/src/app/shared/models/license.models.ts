// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
export interface LicenseInfo {
  /** null for a TENANT_ADMIN caller — the API withholds it to avoid a cross-tenant leak. */
  customer: string | null;
  /** null for a TENANT_ADMIN caller — the API withholds it to avoid a cross-tenant leak. */
  instanceId: string | null;
  /** Epoch seconds, UTC — the same unit the decoded licence key carries. */
  expiresAt: number;
  daysRemaining: number;
  devices: number;
  /** null means unlimited. */
  maxDevices: number | null;
  assets: number;
  /** null means unlimited. */
  maxAssets: number | null;
}

export type LicenseSeverity = 'ok' | 'warn' | 'critical';

export const LICENSE_WARN_DAYS = 30;
export const LICENSE_CRITICAL_DAYS = 7;
export const LICENSE_WARN_PERCENT = 90;
