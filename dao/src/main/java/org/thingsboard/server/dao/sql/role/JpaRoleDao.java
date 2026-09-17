// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.RoleEntity;
import org.thingsboard.server.dao.role.RoleDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@SqlDao
@Slf4j
@RequiredArgsConstructor
public class JpaRoleDao extends JpaAbstractDao<RoleEntity, Role> implements RoleDao {

    private final RoleRepository roleRepository;

    @Override
    protected Class<RoleEntity> getEntityClass() {
        return RoleEntity.class;
    }

    @Override
    protected JpaRepository<RoleEntity, UUID> getRepository() {
        return roleRepository;
    }

    @Override
    public Long countByTenantId(TenantId tenantId) {
        return roleRepository.countByTenantId(tenantId.getId());
    }

    @Override
    public PageData<Role> findAllByTenantId(TenantId tenantId, PageLink pageLink) {
        return DaoUtil.toPageData(roleRepository.findByTenantId(tenantId.getId(),
                pageLink.getTextSearch(), DaoUtil.toPageable(pageLink)));
    }

    @Override
    public Optional<Role> findByTenantIdAndName(UUID tenantId, String name) {
        return roleRepository.findByTenantIdAndName(tenantId, name).map(DaoUtil::getData);
    }

    @Override
    public List<Role> findRolesByIds(UUID tenantId, List<UUID> roleIds) {
        return DaoUtil.convertDataList(roleRepository.findByTenantIdAndIdIn(tenantId, roleIds));
    }

    @Override
    public List<Role> findRolesByUserId(UUID tenantId, UUID userId) {
        return DaoUtil.convertDataList(roleRepository.findByUserId(tenantId, userId));
    }

    @Override
    public boolean lockRole(UUID tenantId, UUID roleId) {
        return roleRepository.lockRole(tenantId, roleId) != null;
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.ROLE;
    }

}
