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

import { BaseData } from '@shared/models/base-data';
import { RoleId } from '@shared/models/id/role-id';
import { TenantId } from '@shared/models/id/tenant-id';

export enum RoleType {
  GENERIC = 'GENERIC',
  GROUP = 'GROUP'
}

// PE-shaped permissions JSON: resource name -> allowed operation names.
// The valid names come from GET /api/permissions/allowedPermissions (server vocabularies),
// deliberately NOT duplicated as FE enums so backend enum changes cannot drift.
export declare type RolePermissions = { [resource: string]: string[] };

export interface Role extends BaseData<RoleId> {
  tenantId?: TenantId;
  name: string;
  type: RoleType;
  permissions: RolePermissions;
  additionalInfo?: { description?: string };
  version?: number;
}

export interface MergedUserPermissions {
  genericPermissions: RolePermissions;
}

export interface AllowedPermissionsInfo {
  // null when the current user has no roles - legacy authority-based access applies
  userPermissions: MergedUserPermissions | null;
  allowedResources: string[];
  allowedOperations: string[];
}
