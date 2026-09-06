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

import { explicitlyHasGenericPermission, hasGenericPermission, menuGroups, menuResources, READ } from '@core/services/menu-permissions';
import { MergedUserPermissions } from '@shared/models/role.models';

const permissions = (genericPermissions: { [resource: string]: string[] }): MergedUserPermissions =>
  ({ genericPermissions } as MergedUserPermissions);

describe('menu permissions', () => {

  it('leaves a role-less user untouched', () => {
    expect(hasGenericPermission(null, 'DEVICE', READ)).toBe(true);
    expect(hasGenericPermission(undefined, 'DEVICE', READ)).toBe(true);
  });

  it('matches resource and operation exactly', () => {
    const readDevices = permissions({DEVICE: ['READ']});
    expect(hasGenericPermission(readDevices, 'DEVICE', READ)).toBe(true);
    expect(hasGenericPermission(readDevices, 'ASSET', READ)).toBe(false);
    expect(hasGenericPermission(permissions({DEVICE: ['WRITE']}), 'DEVICE', READ)).toBe(false);
  });

  it('honours both wildcards', () => {
    expect(hasGenericPermission(permissions({DEVICE: ['ALL']}), 'DEVICE', READ)).toBe(true);
    expect(hasGenericPermission(permissions({ALL: ['READ']}), 'DASHBOARD', READ)).toBe(true);
    expect(hasGenericPermission(permissions({ALL: ['WRITE']}), 'DEVICE', READ)).toBe(false);
  });

  it('denies everything for an empty permission set', () => {
    expect(hasGenericPermission(permissions({}), 'DEVICE', READ)).toBe(false);
  });

  it('denies an explicit-grant capability to a role-less user', () => {
    // the mirror of UserPermissionsUtil.explicitlyGranted: a capability the authority baseline
    // never allowed (customer admin managing peers) must not appear for everyone the day it ships
    expect(explicitlyHasGenericPermission(null, 'USER', 'WRITE')).toBe(false);
    expect(explicitlyHasGenericPermission(permissions({}), 'USER', 'WRITE')).toBe(false);
    expect(explicitlyHasGenericPermission(permissions({USER: ['READ']}), 'USER', 'WRITE')).toBe(false);
    expect(explicitlyHasGenericPermission(permissions({USER: ['WRITE']}), 'USER', 'WRITE')).toBe(true);
    expect(explicitlyHasGenericPermission(permissions({ALL: ['ALL']}), 'USER', 'WRITE')).toBe(true);
  });

  it('maps menu entries and groups', () => {
    expect(Object.keys(menuResources).length).toBeGreaterThan(20);
    expect(menuGroups.length).toBeGreaterThan(10);
    expect(menuResources.roles).toBe('ROLE');
  });

});
