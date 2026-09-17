// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
