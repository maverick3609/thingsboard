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

import { EntityType } from '@shared/models/entity-type.models';
import type { MergedUserPermissions } from '@shared/models/role.models';
import type { BaseData, HasId } from '@shared/models/base-data';
import type { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { hasGenericPermission } from '@core/services/menu-permissions';

/**
 * RBAC phase 4c: which buttons a role can take away from an entity table.
 *
 * Cosmetic only — the server is the enforcer. This just stops a restricted user from opening a
 * form whose save can only answer 403.
 *
 * Nothing here bakes a decision into the config. A table config is built once by a resolver that
 * Angular keeps for the lifetime of the lazy module, and logging out does not reload the page, so
 * a flag set to false for one user would still be false for the next one — including a role-less
 * user, who must see everything. The two function members are therefore wrapped with a wrapper
 * that reads the current permissions when it is called, and the two boolean flags are left alone
 * for the table component to AND at the point of use.
 *
 * Operations match what the controllers check on the write path (`BaseController.checkEntity`):
 * a new entity needs CREATE, an existing one WRITE, removal DELETE. A role holding WRITE but not
 * CREATE therefore keeps edit and loses add, exactly as the server would answer.
 *
 * Known ceiling: on customer- and edge-scoped tables the add button assigns an existing entity
 * instead of creating one, so that same WRITE-without-CREATE role also loses the assign button
 * there. The per-row assign action on the tenant table is untouched, so the path stays open.
 */
export const CREATE = 'CREATE';
export const WRITE = 'WRITE';
export const DELETE = 'DELETE';

/**
 * Entity type to the resource that guards it — the front-end half of `Resource.of(EntityType)`.
 * Kept in step with `application/src/main/java/org/thingsboard/server/service/security/permission/Resource.java`;
 * the four NOTIFICATION_* types share one resource there, everything else is a same-name pair.
 * An entity type missing from this map is never gated. `ADMIN_SETTINGS` and `JOB` carry a
 * resource on the server but have no front-end `EntityType` constant at all. `CALCULATED_FIELD`
 * is left out deliberately: `Resource.CALCULATED_FIELD` only guards READ on the four tenant-wide
 * list endpoints, while writing a calculated field or an alarm rule is checked against its OWNER
 * entity with `WRITE_CALCULATED_FIELD` (`CalculatedFieldController` lines 130 and 261), which a
 * table showing one device's calculated fields cannot know. Gating it here would take the buttons
 * away from a role that holds `DEVICE:[ALL]` and is allowed to use them.
 */
const resourceByEntityType: Partial<Record<EntityType, string>> = {
  [EntityType.ALARM]: 'ALARM',
  [EntityType.DEVICE]: 'DEVICE',
  [EntityType.ASSET]: 'ASSET',
  [EntityType.CUSTOMER]: 'CUSTOMER',
  [EntityType.DASHBOARD]: 'DASHBOARD',
  [EntityType.ENTITY_VIEW]: 'ENTITY_VIEW',
  [EntityType.TENANT]: 'TENANT',
  [EntityType.RULE_CHAIN]: 'RULE_CHAIN',
  [EntityType.USER]: 'USER',
  [EntityType.WIDGETS_BUNDLE]: 'WIDGETS_BUNDLE',
  [EntityType.WIDGET_TYPE]: 'WIDGET_TYPE',
  [EntityType.OAUTH2_CLIENT]: 'OAUTH2_CLIENT',
  [EntityType.DOMAIN]: 'DOMAIN',
  [EntityType.MOBILE_APP]: 'MOBILE_APP',
  [EntityType.MOBILE_APP_BUNDLE]: 'MOBILE_APP_BUNDLE',
  [EntityType.TENANT_PROFILE]: 'TENANT_PROFILE',
  [EntityType.DEVICE_PROFILE]: 'DEVICE_PROFILE',
  [EntityType.ASSET_PROFILE]: 'ASSET_PROFILE',
  [EntityType.API_USAGE_STATE]: 'API_USAGE_STATE',
  [EntityType.TB_RESOURCE]: 'TB_RESOURCE',
  [EntityType.OTA_PACKAGE]: 'OTA_PACKAGE',
  [EntityType.EDGE]: 'EDGE',
  [EntityType.RPC]: 'RPC',
  [EntityType.QUEUE]: 'QUEUE',
  [EntityType.QUEUE_STATS]: 'QUEUE_STATS',
  [EntityType.NOTIFICATION_TARGET]: 'NOTIFICATION',
  [EntityType.NOTIFICATION_TEMPLATE]: 'NOTIFICATION',
  [EntityType.NOTIFICATION_REQUEST]: 'NOTIFICATION',
  [EntityType.NOTIFICATION_RULE]: 'NOTIFICATION',
  [EntityType.SCHEDULER_EVENT]: 'SCHEDULER_EVENT',
  [EntityType.AI_MODEL]: 'AI_MODEL',
  [EntityType.API_KEY]: 'API_KEY',
  [EntityType.REPORT_TEMPLATE]: 'REPORT_TEMPLATE',
  [EntityType.REPORT]: 'REPORT',
  [EntityType.ROLE]: 'ROLE'
};

/**
 * True when the user's roles allow the operation on whatever resource guards the entity type.
 * Entity types with no resource, and users with no roles, are always allowed.
 */
export const entityOperationGranted = (entityType: EntityType,
                                       permissions: MergedUserPermissions | undefined | null,
                                       operation: string): boolean => {
  const resource = entityType ? resourceByEntityType[entityType] : undefined;
  return !resource || hasGenericPermission(permissions, resource, operation);
};

interface RoleGate {
  rbacGate?: boolean;
}

/**
 * Wraps the delete and read-only predicates of a table config so they also answer to the current
 * user's roles: `deleteEnabled` drives the delete row action and the delete button of all 20
 * details components (each reads it through its own `hideDelete()`), `detailsReadonly` drives the
 * edit toggle. Both wrappers narrow, never widen, and both read the permissions when called, so a
 * config that outlives a logout answers for whoever is logged in now.
 *
 * Safe to call on every navigation: a wrapper tags itself, and a resolver that reassigns the raw
 * predicate in `resolve()` simply gets wrapped again.
 */
export const applyRolePermissions = (config: EntityTableConfig<BaseData<HasId>>,
                                     permissions: () => MergedUserPermissions | undefined | null): void => {
  if (!config?.entityType || !resourceByEntityType[config.entityType]) {
    return;
  }
  if (!(config.deleteEnabled as RoleGate).rbacGate) {
    const deleteEnabled = config.deleteEnabled;
    const gatedDeleteEnabled = (entity) =>
      deleteEnabled(entity) && entityOperationGranted(config.entityType, permissions(), DELETE);
    (gatedDeleteEnabled as RoleGate).rbacGate = true;
    config.deleteEnabled = gatedDeleteEnabled;
  }
  if (!(config.detailsReadonly as RoleGate).rbacGate) {
    const detailsReadonly = config.detailsReadonly;
    const gatedDetailsReadonly = (entity) =>
      detailsReadonly(entity) || !entityOperationGranted(config.entityType, permissions(), WRITE);
    (gatedDetailsReadonly as RoleGate).rbacGate = true;
    config.detailsReadonly = gatedDetailsReadonly;
  }
};
