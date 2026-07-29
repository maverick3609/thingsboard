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

import { buildReportStateParam } from './report-state-param';
import { base64toObj, objToBase64 } from '@app/core/utils';

/**
 * BUG 1 (empty-data dashboard-report download) regression spec.
 *
 * downloadReport() used to seed the report dialog with the plain `dashboardCtx.state` id. The
 * report-view route's state controller decodes `?state=` as base64
 * (`default-state-controller.component.ts#parseState` -> `base64toObj`), so a plain id fails to
 * decode and silently falls back to the dashboard's root state - an empty root state yields a
 * produced-but-empty PDF. `buildReportStateParam` is a pure function (no DashboardPageComponent
 * import) so this can be asserted directly against the same decode chain the consumer uses.
 *
 * `buildReportStateParam` returns the URL-safe `objToBase64URI` form (not plain `objToBase64`):
 * `ReportService#openReport` (`report.service.ts:278-281`) concatenates this value RAW into a URL
 * string that `router.navigateByUrl` then parses. Angular's router query-string decoder rewrites a
 * literal `+` to a space before `decodeURIComponent`, so a plain-base64 payload containing `+` (a
 * normal, common base64 character) would come out corrupted - reproducing the exact empty-data bug
 * for those payloads. The specs below therefore decode via `decodeURIComponent(...)` first (what
 * the router's parse step, and separately `default-state-controller.component.ts`'s own
 * `decodeStateParam`, both do) before handing the result to `base64toObj` - the same two-step
 * chain the real consumer applies.
 */
describe('buildReportStateParam', () => {

  it('encodes {id, params} in the base64url form the report-view route decodes (decodeURIComponent then base64toObj)', () => {
    const encoded = buildReportStateParam('state_two', {entityName: 'Boiler 1'});

    const decoded = base64toObj(decodeURIComponent(encoded));

    expect(decoded).toEqual([{id: 'state_two', params: {entityName: 'Boiler 1'}}]);
  });

  it('defaults params to {} when none are supplied (still a valid parseState() result)', () => {
    const encoded = buildReportStateParam('state_two');

    const decoded = base64toObj(decodeURIComponent(encoded));

    expect(decoded).toEqual([{id: 'state_two', params: {}}]);
  });

  it('is NOT the plain id - a bare state id (with hyphens) never survives atob() unchanged', () => {
    const stateId = 'a1b2c3d4-e5f6-7890-uuid-state-id';

    const encoded = buildReportStateParam(stateId);

    expect(encoded).not.toBe(stateId);
    // Sanity: this is exactly the failure mode BUG 1 fixes - the un-encoded id is not valid
    // base64 (contains '-'), so decoding it directly throws, which is what previously drove
    // parseState() into its root-state fallback.
    expect(() => base64toObj(stateId)).toThrow();
  });

  it('returns undefined (no ?state= param at all) when there is no current state - preserves the ' +
    'pre-fix "omit state" behavior for a stateless/not-yet-loaded dashboardCtx', () => {
    expect(buildReportStateParam(null)).toBeUndefined();
    expect(buildReportStateParam(undefined)).toBeUndefined();
    expect(buildReportStateParam('')).toBeUndefined();
  });

  it('single-state / root-state downloads still resolve to the same (only) state id after the round trip', () => {
    // Simulates a single-state dashboard, or a multi-state dashboard downloaded while viewing its
    // root state: dashboardCtx.state === the dashboard's only/root state id either way.
    const rootStateId = 'default';

    const encoded = buildReportStateParam(rootStateId, {});
    const decoded = base64toObj(decodeURIComponent(encoded));

    expect(decoded[0].id).toBe(rootStateId);
  });

  it('URL-safe-encodes the payload so a raw "+" never survives being concatenated into a router-parsed URL', () => {
    // report.service.ts:278-281 concatenates this value RAW into `?state=${command.state}`, which
    // router.navigateByUrl(url) then re-parses (same pattern as the established precedent at
    // widget.component.ts:1157-1176's WidgetActionType.openDashboard handler, which also uses
    // objToBase64URI for exactly this reason). A raw '+' surviving into that string is misread by
    // Angular's router query-string decoder as an encoded space ('+' -> '%20' -> ' ') before
    // decodeURIComponent, corrupting the payload before base64toObj/atob ever sees it - the exact
    // empty-data regression this guards against.
    const stateId = 'state_two';
    const stateParams = {entityName: 'k.D<cw^>m6s?y'}; // constructed: its PLAIN base64 contains '+'

    const plain = objToBase64([{id: stateId, params: stateParams}]);
    expect(plain).toContain('+'); // precondition: proves this input actually exercises the hazard

    const encoded = buildReportStateParam(stateId, stateParams);

    expect(encoded).not.toContain('+');
    expect(encoded).toContain('%2B');
    // still round-trips correctly end to end
    expect(base64toObj(decodeURIComponent(encoded))).toEqual([{id: stateId, params: stateParams}]);
  });
});
