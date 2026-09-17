// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.role;

/**
 * PE-compatible role type. Option B of the RBAC port only supports GENERIC roles
 * (resource → operations maps); GROUP is kept so PE role JSON round-trips and a future
 * entity-group port does not need a data migration.
 */
public enum RoleType {
    GENERIC,
    GROUP
}
