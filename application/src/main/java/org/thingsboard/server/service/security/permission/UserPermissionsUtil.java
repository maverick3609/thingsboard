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

import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.query.EntityFilter;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.sql.query.DefaultEntityQueryRepository;
import org.thingsboard.server.service.security.model.SecurityUser;

/**
 * RBAC port (Option B): the single AND-in point used by DefaultAccessControlService.
 * Roles only ever RESTRICT within the authority's baseline permissions — the per-authority
 * checkers run first and this gate runs after, so a role can never grant what the authority's
 * checker set denies.
 */
public final class UserPermissionsUtil {

    private UserPermissionsUtil() {
    }

    /**
     * True when the user's roles allow the operation on the resource.
     * <ul>
     *   <li>Authorities other than TENANT_ADMIN / CUSTOMER_USER (SYS_ADMIN, MFA / pre-verification
     *       tokens) are never role-restricted.</li>
     *   <li>A user with no roles ({@code userPermissions == null}) keeps legacy full access.</li>
     * </ul>
     */
    public static boolean granted(SecurityUser user, Resource resource, Operation operation) {
        Authority authority = user.getAuthority();
        if (authority != Authority.TENANT_ADMIN && authority != Authority.CUSTOMER_USER) {
            return true;
        }
        MergedUserPermissions userPermissions = user.getUserPermissions();
        if (userPermissions == null) {
            return true;
        }
        return userPermissions.hasGenericPermission(resource, operation);
    }

    /**
     * Role gate for entity data/count queries (REST /api/entitiesQuery/* and the WS v2 cmds):
     * the query's entity filter resolves to one entity type — a role-restricted user needs READ
     * on that type's resource. Filters that cannot be resolved or map to no Resource are not
     * role-gated (legacy behavior); tenant/customer scoping inside the query builder still applies.
     */
    public static boolean grantedEntityQuery(SecurityUser user, EntityFilter filter) {
        Authority authority = user.getAuthority();
        if (authority != Authority.TENANT_ADMIN && authority != Authority.CUSTOMER_USER) {
            return true;
        }
        if (user.getUserPermissions() == null || filter == null) {
            return true;
        }
        EntityType entityType;
        try {
            entityType = DefaultEntityQueryRepository.resolveEntityType(filter);
        } catch (Exception e) {
            return true;
        }
        Resource resource;
        try {
            resource = Resource.of(entityType);
        } catch (IllegalArgumentException e) {
            return true;
        }
        return granted(user, resource, Operation.READ);
    }

}
