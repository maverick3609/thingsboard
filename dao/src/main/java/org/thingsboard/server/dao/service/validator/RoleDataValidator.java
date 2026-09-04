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
package org.thingsboard.server.dao.service.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.exception.DataValidationException;

/**
 * Structural validation only. Semantic validation of the permissions JSON (resource and
 * operation names against the enums) happens in the application layer (DefaultTbRoleService),
 * because the Resource/Operation enums live in the application module, not in dao.
 */
@Component
@Slf4j
public class RoleDataValidator extends DataValidator<Role> {

    static final int MAX_PERMISSIONS_JSON_LENGTH = 65536;

    @Autowired
    private TenantService tenantService;

    @Override
    protected void validateCreate(TenantId tenantId, Role role) {
        validateNumberOfEntitiesPerTenant(tenantId, EntityType.ROLE);
    }

    @Override
    protected void validateDataImpl(TenantId tenantId, Role role) {
        validateString("Role name", role.getName());
        if (role.getType() == null) {
            throw new DataValidationException("Role type should be specified!");
        }
        if (role.getType() != RoleType.GENERIC) {
            throw new DataValidationException("Only GENERIC roles are supported!");
        }
        if (role.getTenantId() == null) {
            throw new DataValidationException("Role should be assigned to tenant!");
        }
        if (!tenantService.tenantExists(role.getTenantId())) {
            throw new DataValidationException("Role is referencing to non-existent tenant!");
        }
        JsonNode permissions = role.getPermissions();
        if (permissions != null && !permissions.isNull()) {
            if (!permissions.isObject()) {
                throw new DataValidationException("Role permissions should be a JSON object!");
            }
            if (permissions.toString().length() > MAX_PERMISSIONS_JSON_LENGTH) {
                throw new DataValidationException("Role permissions JSON is too large!");
            }
        }
    }

}
