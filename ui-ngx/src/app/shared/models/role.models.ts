// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
