// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
// 'DEVICE_PROFILE' -> 'Device profile'. Vocabulary names come from the server
// (allowedPermissions) rather than FE enums, so labels are derived, not translated.
export const humanizePermissionName = (name: string): string => {
  if (!name) {
    return '';
  }
  const lower = name.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
};
