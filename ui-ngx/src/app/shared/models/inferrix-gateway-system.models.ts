// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0

/** What `GET /v2/about` reports. */
export interface GatewayAbout {
  stackVersion?: string;
  schemaVersion?: number;
  hostName?: string;
  startTime?: number;
  uptimeMillis?: number;
  modules?: {name?: string; version?: string}[];
  license?: {entered?: boolean; valid?: boolean; used?: number; available?: number};
}

/** `GET /v2/stack-monitor` — a bare array, not a page. */
export interface GatewayMonitorValue {
  name?: string;
  value?: any;
}

/** `GET /v2/server/network-interfaces` — a bare array. */
export interface GatewayNetworkInterface {
  interfaceName?: string;
  hostAddress?: string;
}

/** `GET /v2/server/languages` — a bare array. */
export interface GatewayLanguage {
  key?: string;
  value?: string;
}

/**
 * `GET /v2/system-setting` — one flat object of about a hundred keys, not a page and not a model.
 *
 * Keys are the gateway's own camelCase property names and are shown as-is. They are what the
 * gateway's documentation and its own interface call these settings, so translating or prettifying
 * them would make a diagnostics table harder to match against the thing it describes, not easier.
 */
export type GatewaySystemSettings = {[key: string]: any};

/**
 * Settings whose values this UI will not paint into a page.
 *
 * Reading them is already permitted — the route is tenant-admin only and the same administrator can
 * fetch the object through the proxy directly — so this is not an access control. It is surface: a
 * diagnostics table that an operator may screenshot or paste into a ticket has no business carrying
 * the gateway's licence blob or a third-party API token, neither of which diagnoses anything.
 *
 * A current gateway withholds these itself: stack fix D7 (2026-09-23) put the licence blob and
 * `poeLightingToken` behind an explicit registry beside its own name pattern, so `GET
 * /v2/system-setting` now answers 99 keys rather than 101 and none of them is a secret. This stays
 * anyway, for two reasons that outlast that fix: an older gateway is still adoptable and still
 * sends both, and the suffix rules cover the next `…Token` or `…Secret` a later gateway version
 * adds, on either side of the wire. Nothing in the current hundred keys matches them by accident.
 */
const WITHHELD_KEYS = new Set<string>(['license']);
const WITHHELD_SUFFIX = /(token|password|secret|passphrase|privatekey)$/i;

export const gatewaySettingWithheld = (key: string): boolean =>
  WITHHELD_KEYS.has(key) || WITHHELD_SUFFIX.test(key);

/**
 * One setting's value as a line of text.
 *
 * Values are a mix of string, number and boolean, and an empty string is a real and common value
 * that would otherwise render as a blank cell indistinguishable from a missing row.
 */
export const gatewaySettingValue = (value: any): string => {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'string') {
    return value === '' ? '—' : value;
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
};

/** The settings worth showing, key-sorted, with the withheld ones dropped. */
export const gatewaySettingRows = (settings: GatewaySystemSettings): {key: string; value: string}[] =>
  Object.keys(settings ?? {})
    .filter(key => !gatewaySettingWithheld(key))
    .sort()
    .map(key => ({key, value: gatewaySettingValue(settings[key])}));
