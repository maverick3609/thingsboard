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
import { base64toObj } from '@app/core/utils';

/**
 * BUG 1 (empty-data dashboard-report download) regression spec.
 *
 * downloadReport() used to seed the report dialog with the plain `dashboardCtx.state` id. The
 * report-view route's state controller decodes `?state=` as base64
 * (`default-state-controller.component.ts#parseState` -> `base64toObj`), so a plain id fails to
 * decode and silently falls back to the dashboard's root state - an empty root state yields a
 * produced-but-empty PDF. `buildReportStateParam` is a pure function (no DashboardPageComponent
 * import) so this can be asserted directly against the same `base64toObj` the consumer uses.
 */
describe('buildReportStateParam', () => {

  it('encodes {id, params} in the base64 form the report-view route decodes via base64toObj', () => {
    const encoded = buildReportStateParam('state_two', {entityName: 'Boiler 1'});

    // Round-trip through the exact decode function default-state-controller.component.ts uses.
    const decoded = base64toObj(encoded);

    expect(decoded).toEqual([{id: 'state_two', params: {entityName: 'Boiler 1'}}]);
  });

  it('defaults params to {} when none are supplied (still a valid parseState() result)', () => {
    const encoded = buildReportStateParam('state_two');

    const decoded = base64toObj(encoded);

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
    const decoded = base64toObj(encoded);

    expect(decoded[0].id).toBe(rootStateId);
  });
});
