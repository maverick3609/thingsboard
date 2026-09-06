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
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.query.EntityFilter;
import org.thingsboard.server.common.data.query.RelationsQueryFilter;
import org.thingsboard.server.common.data.relation.RelationEntityTypeFilter;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.sql.query.DefaultEntityQueryRepository;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
     *   <li>Only CUSTOMER_USER is ever role-restricted. A tenant admin has full rights over their
     *       own domain and a sys admin over the platform, so both are exempt - and
     *       {@code DefaultUserPermissionsService} never even loads permissions for them. This
     *       check is the second half of that invariant, so a stray call site that attaches
     *       permissions to a tenant admin still cannot restrict one.</li>
     *   <li>A user with no roles ({@code userPermissions == null}) keeps legacy full access.</li>
     * </ul>
     */
    public static boolean granted(SecurityUser user, Resource resource, Operation operation) {
        if (user.getAuthority() != Authority.CUSTOMER_USER) {
            return true;
        }
        MergedUserPermissions userPermissions = user.getUserPermissions();
        if (userPermissions == null) {
            return true;
        }
        return userPermissions.hasGenericPermission(resource, operation);
    }

    /**
     * A capability that is OFF until a role explicitly turns it on — the inverse of
     * {@link #granted}, which reads "no roles" as legacy full access.
     * <p>
     * Use this, never {@code granted}, when opening an operation the authority baseline has never
     * allowed. With {@code granted} a role-less user would sail straight through the null check and
     * silently gain the new power; here they are denied until someone assigns a role that names it.
     */
    public static boolean explicitlyGranted(SecurityUser user, Resource resource, Operation operation) {
        MergedUserPermissions userPermissions = user.getUserPermissions();
        return userPermissions != null && userPermissions.hasGenericPermission(resource, operation);
    }

    /**
     * Entity-scoped role gate, with two READ exemptions for the entities a user IS rather than
     * the entities a user can see. Both are things the authority checker already confines to the
     * user themselves, so gating them buys no security and only breaks the account:
     * <ul>
     *   <li>your own user record - every authenticated session bootstraps with
     *       {@code GET /api/user/{self}}, so a role without {@code USER:READ} would lock its
     *       holders out of the platform entirely;</li>
     *   <li>a customer user's own customer - {@code checkCustomerId} guards every customer-scoped
     *       list ({@code /api/customer/{id}/devices} and friends), so a role without
     *       {@code CUSTOMER:READ} would deny them regardless of what else it grants.</li>
     * </ul>
     * Only READ is exempt: editing your own profile or customer still needs the grant.
     */
    public static boolean granted(SecurityUser user, Resource resource, Operation operation, EntityId entityId) {
        if (operation == Operation.READ && ownEntity(user, resource, entityId)) {
            return true;
        }
        return granted(user, resource, operation);
    }

    private static boolean ownEntity(SecurityUser user, Resource resource, EntityId entityId) {
        if (resource == Resource.USER) {
            return user.getId() != null && user.getId().equals(entityId);
        }
        if (resource == Resource.CUSTOMER) {
            // tenant admins read many customers - only a customer user's own customer is exempt
            return user.getAuthority() == Authority.CUSTOMER_USER
                    && user.getCustomerId() != null && user.getCustomerId().equals(entityId);
        }
        return false;
    }

    /**
     * Role gate for entity data/count queries (REST /api/entitiesQuery/* and the WS v2 cmds):
     * the query's entity filter resolves to one entity type — a role-restricted user needs READ
     * on that type's resource. Filters that cannot be resolved or map to no Resource are not
     * role-gated (legacy behavior); tenant/customer scoping inside the query builder still applies.
     */
    public static boolean grantedEntityQuery(SecurityUser user, EntityFilter filter) {
        if (user.getAuthority() != Authority.CUSTOMER_USER) {
            return true;
        }
        if (user.getUserPermissions() == null || filter == null) {
            return true;
        }
        if (filter instanceof RelationsQueryFilter relationsFilter) {
            return grantedRelationsQuery(user, relationsFilter);
        }
        EntityType entityType;
        try {
            entityType = DefaultEntityQueryRepository.resolveEntityType(filter);
        } catch (Exception e) {
            return true;
        }
        return grantedRead(user, entityType);
    }

    /**
     * A relations query returns the entities RELATED to the root, not the root itself, so the
     * root's entity type says nothing about what leaves the system: the row types come from the
     * relation filters. The user needs READ on every type the query can return.
     */
    private static boolean grantedRelationsQuery(SecurityUser user, RelationsQueryFilter filter) {
        for (EntityType entityType : relationsQueryResultTypes(filter)) {
            if (!grantedRead(user, entityType)) {
                return false;
            }
        }
        return true;
    }

    private static Collection<EntityType> relationsQueryResultTypes(RelationsQueryFilter filter) {
        List<RelationEntityTypeFilter> typeFilters = filter.getFilters();
        // negate turns the where clause into its complement, and a filter without entity types
        // matches on relation type alone — either way any relation-queryable type can come back.
        if (filter.isNegate() || typeFilters == null || typeFilters.isEmpty()) {
            return List.of(DefaultEntityQueryRepository.RELATION_QUERY_ENTITY_TYPES);
        }
        Set<EntityType> entityTypes = EnumSet.noneOf(EntityType.class);
        for (RelationEntityTypeFilter typeFilter : typeFilters) {
            List<EntityType> filterTypes = typeFilter.getEntityTypes();
            if (filterTypes == null || filterTypes.isEmpty()) {
                return List.of(DefaultEntityQueryRepository.RELATION_QUERY_ENTITY_TYPES);
            }
            entityTypes.addAll(filterTypes);
        }
        return entityTypes;
    }

    private static boolean grantedRead(SecurityUser user, EntityType entityType) {
        // null for a dashboard alias id (CURRENT_TENANT / CURRENT_CUSTOMER / CURRENT_USER):
        // AliasEntityIdImpl leaves entityType unset until the alias is resolved, and the alias is
        // still unresolved when this gate runs. Not role-gated, like any other unresolvable filter.
        if (entityType == null) {
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
