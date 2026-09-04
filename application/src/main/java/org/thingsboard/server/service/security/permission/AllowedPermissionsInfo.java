/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
