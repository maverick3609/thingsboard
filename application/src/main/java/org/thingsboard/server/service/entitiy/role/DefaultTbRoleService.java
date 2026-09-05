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
package org.thingsboard.server.service.entitiy.role;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.role.RoleService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;
import org.thingsboard.server.service.security.permission.UserPermissionsService;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@TbCoreComponent
@RequiredArgsConstructor
@Slf4j
public class DefaultTbRoleService extends AbstractTbEntityService implements TbRoleService {

    private final RoleService roleService;
    private final UserPermissionsService userPermissionsService;

    @Override
    public Role save(Role role, User user) throws ThingsboardException {
        ActionType actionType = role.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        log.debug("[{}] Saving role [{}]", user.getTenantId(), role.getName());
        validatePermissionsJson(role.getPermissions());
        try {
            Role savedRole = checkNotNull(roleService.saveRole(role));
            if (actionType == ActionType.UPDATED) {
                userPermissionsService.onRoleUpdated(user.getTenantId(), savedRole.getId());
            }
            logEntityActionService.logEntityAction(user.getTenantId(), savedRole.getId(), savedRole, null, actionType, user);
            log.info("[{}][{}] Saved role [{}]", user.getTenantId(), savedRole.getId(), savedRole.getName());
            return savedRole;
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.ROLE), (HasName) role, actionType, user, e);
            throw e;
        }
    }

    @Override
    public void delete(Role role, User user) throws ThingsboardException {
        RoleId roleId = role.getId();
        log.debug("[{}][{}] Deleting role [{}]", user.getTenantId(), roleId, role.getName());
        try {
            // capture assignees before the delete: the FK cascade removes user_role rows
            List<UserId> assignees = roleService.findUserIdsByRoleId(user.getTenantId(), roleId);
            roleService.deleteRole(user.getTenantId(), roleId);
            userPermissionsService.onRoleDeleted(user.getTenantId(), roleId, assignees);
            logEntityActionService.logEntityAction(user.getTenantId(), (EntityId) roleId, role, null, ActionType.DELETED, user, roleId.getId());
            log.info("[{}][{}] Deleted role [{}]", user.getTenantId(), roleId, role.getName());
        } catch (Exception e) {
            logEntityActionService.logEntityAction(user.getTenantId(), emptyId(EntityType.ROLE), ActionType.DELETED, user, e, roleId.getId());
            throw e;
        }
    }

    @Override
    public void updateUserRoles(User targetUser, List<RoleId> roleIds, User user) throws ThingsboardException {
        log.debug("[{}] Updating roles of user [{}] to [{}]", user.getTenantId(), targetUser.getId(), roleIds);
        if (roleIds != null && !roleIds.isEmpty()) {
            List<Role> found = roleService.findRolesByIds(user.getTenantId(), roleIds);
            if (found.size() != new HashSet<>(roleIds).size()) {
                throw new ThingsboardException("One or more roles do not exist!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
            }
        }
        roleService.updateUserRoles(user.getTenantId(), targetUser.getId(), roleIds);
        userPermissionsService.onUserRolesUpdated(user.getTenantId(), targetUser.getId());
        logEntityActionService.logEntityAction(user.getTenantId(), targetUser.getId(), targetUser,
                targetUser.getCustomerId(), ActionType.UPDATED, user);
    }

    /**
     * Semantic validation of the PE-shaped permissions JSON: every key must be a known
     * {@link Resource}, every value an array of known {@link Operation} names. Lives here (not in
     * the dao validator) because the Resource/Operation enums belong to the application module.
     */
    static void validatePermissionsJson(JsonNode permissions) throws ThingsboardException {
        if (permissions == null || permissions.isNull() || !permissions.isObject()) {
            throw new ThingsboardException("Role permissions should be a JSON object!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        if (permissions.isEmpty()) {
            // an empty object is not a no-op role: it merges to an empty permission set, which
            // denies every operation on every resource for everyone it is assigned to.
            throw new ThingsboardException("Role permissions should not be empty!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        }
        Set<String> resourceNames = new HashSet<>();
        for (Resource resource : Resource.values()) {
            resourceNames.add(resource.name());
        }
        Set<String> operationNames = new HashSet<>();
        for (Operation operation : Operation.values()) {
            operationNames.add(operation.name());
        }
        for (Map.Entry<String, JsonNode> entry : permissions.properties()) {
            if (!resourceNames.contains(entry.getKey())) {
                throw new ThingsboardException("Unknown resource: " + entry.getKey(), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
            }
            JsonNode operations = entry.getValue();
            if (!operations.isArray()) {
                throw new ThingsboardException("Operations of resource " + entry.getKey() + " should be an array!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
            }
            for (JsonNode operation : operations) {
                if (!operation.isTextual() || !operationNames.contains(operation.asText())) {
                    throw new ThingsboardException("Unknown operation for resource " + entry.getKey() + ": " + operation.asText(), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
                }
            }
        }
    }

}
