// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * FE contract of GET /api/permissions/allowedPermissions (PE-parity endpoint, simplified):
 * the caller's own merged role permissions plus the vocabularies the role editor and the
 * menu/button gating need.
 */
@Data
@AllArgsConstructor
public class AllowedPermissionsInfo {

    @Schema(description = "Merged permissions of all roles assigned to the current user; null when the user has no roles (legacy authority-based access applies)")
    private final MergedUserPermissions userPermissions;

    @Schema(description = "Vocabulary: all resource names usable as keys of a role's permissions JSON")
    private final List<Resource> allowedResources;

    @Schema(description = "Vocabulary: all operation names usable inside a role's permissions JSON")
    private final List<Operation> allowedOperations;

}
