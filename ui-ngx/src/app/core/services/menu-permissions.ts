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

import { MergedUserPermissions } from '@shared/models/role.models';

/**
 * RBAC phase 4b: which menu entries a role can hide.
 *
 * Cosmetic only — the server is the enforcer (`UserPermissionsUtil`, and the read gate on the
 * collection endpoints). This just stops a restricted user from clicking into pages that would
 * only answer 403.
 *
 * Resource names are the backend `Resource` enum constants. They are strings rather than a
 * generated enum so the FE keeps no copy of the vocabulary to drift out of sync — the trade is
 * that a typo hides a menu entry for every user who has a role, so verify a new name against
 * `application/src/main/java/org/thingsboard/server/service/security/permission/Resource.java`.
 */
export const READ = 'READ';

const ALL_RESOURCES = 'ALL';
const ALL_OPERATIONS = 'ALL';

/**
 * True when the user's roles allow the operation on the resource. A user with no roles keeps the
 * legacy authority-based access, so everything stays visible for them.
 */
export const hasGenericPermission = (permissions: MergedUserPermissions | undefined | null,
                                     resource: string, operation: string): boolean => {
  if (!permissions?.genericPermissions) {
    return true;
  }
  const granted = permissions.genericPermissions;
  return allows(granted[resource], operation) || allows(granted[ALL_RESOURCES], operation);
};

const allows = (operations: string[] | undefined, operation: string): boolean =>
  !!operations && (operations.includes(ALL_OPERATIONS) || operations.includes(operation));

/**
 * Menu entry id to the resource whose READ it exposes. Ids not listed here are never hidden:
 * the user's own inbox and profile, platform settings pages, and anything whose data has no one
 * resource behind it (the IoT hub inventory, version control).
 */
export const menuResources: { [menuId: string]: string } = {
  alarms: 'ALARM',
  alarm_rules: 'CALCULATED_FIELD',
  dashboards: 'DASHBOARD',
  devices: 'DEVICE',
  gateways: 'DEVICE',
  assets: 'ASSET',
  entity_views: 'ENTITY_VIEW',
  device_profiles: 'DEVICE_PROFILE',
  asset_profiles: 'ASSET_PROFILE',
  customers: 'CUSTOMER',
  calculated_fields: 'CALCULATED_FIELD',
  rule_chains: 'RULE_CHAIN',
  rulechain_templates: 'RULE_CHAIN',
  edge_instances: 'EDGE',
  scheduler: 'SCHEDULER_EVENT',
  images: 'TB_RESOURCE',
  scada_symbols: 'TB_RESOURCE',
  javascript_library: 'TB_RESOURCE',
  resources_library: 'TB_RESOURCE',
  widget_types: 'WIDGET_TYPE',
  widgets_bundles: 'WIDGETS_BUNDLE',
  api_usage: 'API_USAGE_STATE',
  mobile_apps: 'MOBILE_APP',
  mobile_bundles: 'MOBILE_APP_BUNDLE',
  ai_models: 'AI_MODEL',
  white_labeling: 'WHITE_LABELING',
  roles: 'ROLE',
  otaUpdates: 'OTA_PACKAGE',
  queues: 'QUEUE',
  audit_log: 'AUDIT_LOG',
  clients: 'OAUTH2_CLIENT',
  domains: 'DOMAIN',
  notification_sent: 'NOTIFICATION',
  notification_recipients: 'NOTIFICATION',
  notification_templates: 'NOTIFICATION',
  notification_rules: 'NOTIFICATION',
  report_templates: 'REPORT_TEMPLATE',
  report_history: 'REPORT',
  report_scheduling: 'SCHEDULER_EVENT'
};

/**
 * Group entries with no resource of their own. They are listed so the menu builder evaluates
 * their children and drops a group whose every page is hidden — its filter passes, the
 * all-pages-hidden check in `filterMenuReference` then removes it.
 */
export const menuGroups: string[] = [
  'alarms_center', 'entities', 'profiles', 'features', 'resources', 'widget_library',
  'utilities', 'mobile_center', 'settings_center', 'settings', 'security_settings',
  'oauth2', 'notifications_center', 'reports'
];
