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

import { objToBase64URI } from '@app/core/utils';
import type { StateObject, StateParams } from '@core/api/widget-api.models';

/**
 * Builds the `?state=` value the report-view route's state controller expects
 * (`DefaultStateControllerComponent`/`EntityStateControllerComponent#parseState()` decodes it via
 * `base64toObj` - see `default-state-controller.component.ts`). Kept in its own file (no
 * DashboardPageComponent import) so it has a lightweight, standalone unit-test seam - that file's
 * own transitive import graph (VersionControlComponent et al.) is far too heavy for a fast spec.
 *
 * BUG 1 fix: `DashboardPageComponent#downloadReport()` used to hand the report dialog the plain
 * `dashboardCtx.state` id. The report-view route base64-decodes `?state=`, so a plain id fails to
 * decode and silently falls back to the dashboard's root state (see `parseState`'s catch branch) -
 * if that root/fallback state has no widgets, the rendered PDF comes back with no data.
 *
 * Uses `objToBase64URI` (NOT plain `objToBase64`): the consumer, `ReportService#openReport`
 * (`report.service.ts:278-281`), concatenates this value RAW into a URL string
 * (`` `?state=${command.state}` ``) that `router.navigateByUrl(url)` then parses - it is not passed
 * as a `queryParams` object. Angular's router query-string decoder treats a literal `+` in that raw
 * string as an encoded space (`+` -> `%20` -> ` `) before `decodeURIComponent`, corrupting any
 * base64 payload that happens to contain `+` (a common, entirely normal base64 character) and
 * reproducing the exact same empty-data symptom this fix targets, for those payloads. `objToBase64URI`
 * (`encodeURIComponent(objToBase64(obj))`) percent-escapes `+`/`/`/`=` before concatenation, and the
 * router's own decode-on-parse step (plus `default-state-controller.component.ts`'s own
 * `decodeStateParam`'s `decodeURIComponent`) correctly restores the original base64 string before
 * `parseState`/`base64toObj` ever see it. This mirrors the codebase's own established convention for
 * this exact scenario: `widget.component.ts`'s `WidgetActionType.openDashboard` handler builds
 * `objToBase64URI([stateObject])` then likewise does `` `/dashboard/${id}?state=${state}` `` followed
 * by `router.navigateByUrl(url)` - the identical raw-concatenation pattern. (Contrast the plain
 * `objToBase64` call sites in `widget.component.ts`/`dashboard-state.component.ts` that instead hand
 * their value through a `queryParams`/`@Input` object - not a raw concatenated URL string - so they
 * don't need this extra layer.)
 */
export function buildReportStateParam(stateId: string, stateParams?: StateParams): string {
  if (!stateId) {
    return undefined;
  }
  const stateObject: StateObject = {id: stateId, params: stateParams || {}};
  return objToBase64URI([stateObject]);
}
