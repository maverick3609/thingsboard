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

import { objToBase64 } from '@app/core/utils';
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
 * if that root/fallback state has no widgets, the rendered PDF comes back with no data. Wrapping
 * {id, params} the same way `updateLocation()`/`objToBase64([stateObject])` already do elsewhere
 * (e.g. `widget.component.ts`, `dashboard-state.component.ts`) matches the consumer's expected
 * encoding.
 */
export function buildReportStateParam(stateId: string, stateParams?: StateParams): string {
  if (!stateId) {
    return undefined;
  }
  const stateObject: StateObject = {id: stateId, params: stateParams || {}};
  return objToBase64([stateObject]);
}
