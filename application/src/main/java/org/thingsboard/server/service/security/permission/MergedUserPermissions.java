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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * RBAC port (Option B): the union of all GENERIC roles assigned to a user, computed by
 * {@link UserPermissionsService} and attached to the SecurityUser on every JWT parse.
 * PE-compatible shape minus the entity-group maps (CE has no entity groups): only
 * genericPermissions — resource to allowed-operations. Serializable + Jackson-friendly
 * for the caffeine/redis permissions cache.
 */
public final class MergedUserPermissions implements Serializable {

    private static final long serialVersionUID = 1L;

    @Getter
    private final Map<Resource, Set<Operation>> genericPermissions;

    @JsonCreator
    public MergedUserPermissions(@JsonProperty("genericPermissions") Map<Resource, Set<Operation>> genericPermissions) {
        this.genericPermissions = genericPermissions;
    }

    public boolean hasGenericPermission(Resource resource, Operation operation) {
        return hasGenericResourcePermission(resource, operation) || hasGenericAllPermission(operation);
    }

    private boolean hasGenericAllPermission(Operation operation) {
        Set<Operation> operations = genericPermissions.get(Resource.ALL);
        return operations != null && checkOperation(operations, operation);
    }

    private boolean hasGenericResourcePermission(Resource resource, Operation operation) {
        Set<Operation> operations = genericPermissions.get(resource);
        return operations != null && checkOperation(operations, operation);
    }

    private boolean checkOperation(Set<Operation> operations, Operation operation) {
        return operations.contains(Operation.ALL) || operations.contains(operation);
    }

}
