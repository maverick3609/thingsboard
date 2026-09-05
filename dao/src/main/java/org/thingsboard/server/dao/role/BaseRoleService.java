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
package org.thingsboard.server.dao.role;

import com.google.common.util.concurrent.FluentFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.entity.EntityCountService;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.service.Validator;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

@Service("RoleDaoService")
@Slf4j
public class BaseRoleService extends AbstractEntityService implements RoleService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_ROLE_ID = "Incorrect roleId ";
    public static final String INCORRECT_USER_ID = "Incorrect userId ";

    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserRoleDao userRoleDao;
    @Autowired
    private DataValidator<Role> roleDataValidator;
    @Autowired
    private EntityCountService entityCountService;

    @Override
    public Role saveRole(Role role) {
        log.debug("Executing saveRole, tenantId [{}], name [{}]", role.getTenantId(), role.getName());
        roleDataValidator.validate(role, Role::getTenantId);
        try {
            boolean created = role.getId() == null;
            Role savedRole = roleDao.save(role.getTenantId(), role);
            if (created) {
                entityCountService.publishCountEntityEvictEvent(savedRole.getTenantId(), EntityType.ROLE);
            }
            eventPublisher.publishEvent(SaveEntityEvent.builder().tenantId(savedRole.getTenantId()).entityId(savedRole.getId())
                    .entity(savedRole).created(created).build());
            return savedRole;
        } catch (Exception e) {
            checkConstraintViolation(e, "role_name_unq_key", "Role with such name already exists!");
            throw e;
        }
    }

    @Override
    public Role findRoleById(TenantId tenantId, RoleId roleId) {
        log.debug("Executing findRoleById, tenantId [{}], roleId [{}]", tenantId, roleId);
        Validator.validateId(roleId, id -> INCORRECT_ROLE_ID + id);
        return roleDao.findById(tenantId, roleId.getId());
    }

    @Override
    public List<Role> findRolesByIds(TenantId tenantId, List<RoleId> roleIds) {
        log.debug("Executing findRolesByIds, tenantId [{}], roleIds [{}]", tenantId, roleIds);
        Validator.validateIds(roleIds, ids -> "Incorrect roleIds " + ids);
        return roleDao.findRolesByIds(tenantId.getId(), DaoUtil.toUUIDs(roleIds));
    }

    @Override
    public PageData<Role> findRolesByTenantId(TenantId tenantId, PageLink pageLink) {
        log.debug("Executing findRolesByTenantId, tenantId [{}], pageLink [{}]", tenantId, pageLink);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validatePageLink(pageLink);
        return roleDao.findAllByTenantId(tenantId, pageLink);
    }

    @Override
    public Optional<Role> findRoleByTenantIdAndName(TenantId tenantId, String name) {
        log.debug("Executing findRoleByTenantIdAndName, tenantId [{}], name [{}]", tenantId, name);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        return roleDao.findByTenantIdAndName(tenantId.getId(), name);
    }

    @Override
    public void deleteRole(TenantId tenantId, RoleId roleId) {
        log.debug("Executing deleteRole, tenantId [{}], roleId [{}]", tenantId, roleId);
        Validator.validateId(roleId, id -> INCORRECT_ROLE_ID + id);
        Role role = findRoleById(tenantId, roleId);
        if (role == null) {
            return;
        }
        // user_role rows are removed by FK ON DELETE CASCADE
        roleDao.removeById(tenantId, roleId.getId());
        entityCountService.publishCountEntityEvictEvent(tenantId, EntityType.ROLE);
        eventPublisher.publishEvent(DeleteEntityEvent.builder().tenantId(tenantId).entityId(roleId).entity(role).build());
    }

    @Override
    public List<Role> findRolesByUserId(TenantId tenantId, UserId userId) {
        log.debug("Executing findRolesByUserId, tenantId [{}], userId [{}]", tenantId, userId);
        Validator.validateId(userId, id -> INCORRECT_USER_ID + id);
        // single join over user_role; tenant-scoped so a row can never cross tenants
        return roleDao.findRolesByUserId(tenantId.getId(), userId.getId());
    }

    @Override
    public List<UserId> findUserIdsByRoleId(TenantId tenantId, RoleId roleId) {
        log.debug("Executing findUserIdsByRoleId, tenantId [{}], roleId [{}]", tenantId, roleId);
        Validator.validateId(roleId, id -> INCORRECT_ROLE_ID + id);
        // honor the tenant-scoped signature: a foreign roleId must not disclose another tenant's
        // user ids. The tenantId argument of findById is ignored by JpaAbstractDao, so compare here.
        Role role = roleDao.findById(tenantId, roleId.getId());
        if (role == null || !tenantId.getId().equals(role.getTenantId().getId())) {
            return Collections.emptyList();
        }
        return userRoleDao.findUserIdsByRoleId(roleId);
    }

    @Override
    @Transactional
    public void updateUserRoles(TenantId tenantId, UserId userId, List<RoleId> roleIds) {
        log.debug("Executing updateUserRoles, tenantId [{}], userId [{}], roleIds [{}]", tenantId, userId, roleIds);
        Validator.validateId(userId, id -> INCORRECT_USER_ID + id);
        userRoleDao.replaceUserRoles(tenantId, userId, roleIds);
    }

    @Override
    public Optional<HasId<?>> findEntity(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(findRoleById(tenantId, new RoleId(entityId.getId())));
    }

    @Override
    public FluentFuture<Optional<HasId<?>>> findEntityAsync(TenantId tenantId, EntityId entityId) {
        return FluentFuture.from(roleDao.findByIdAsync(tenantId, entityId.getId()))
                .transform(Optional::ofNullable, directExecutor());
    }

    @Override
    public void deleteEntity(TenantId tenantId, EntityId id, boolean force) {
        deleteRole(tenantId, (RoleId) id);
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        log.debug("Executing deleteByTenantId, tenantId [{}]", tenantId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        tenantRolesRemover.removeEntities(tenantId, tenantId);
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.ROLE;
    }

    private final PaginatedRemover<TenantId, Role> tenantRolesRemover = new PaginatedRemover<>() {

        @Override
        protected PageData<Role> findEntities(TenantId tenantId, TenantId id, PageLink pageLink) {
            return roleDao.findAllByTenantId(id, pageLink);
        }

        @Override
        protected void removeEntity(TenantId tenantId, Role role) {
            deleteRole(tenantId, role.getId());
        }
    };

}
