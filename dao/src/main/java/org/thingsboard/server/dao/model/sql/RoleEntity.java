// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLJsonPGObjectJsonbType;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.server.dao.model.BaseVersionedEntity;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.util.mapping.JsonConverter;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = ModelConstants.ROLE_TABLE_NAME)
public final class RoleEntity extends BaseVersionedEntity<Role> {

    @Column(name = ModelConstants.TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ModelConstants.ROLE_NAME_PROPERTY)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = ModelConstants.ROLE_TYPE_PROPERTY)
    private RoleType type;

    @Convert(converter = JsonConverter.class)
    @JdbcType(PostgreSQLJsonPGObjectJsonbType.class)
    @Column(name = ModelConstants.ROLE_PERMISSIONS_PROPERTY, columnDefinition = "jsonb")
    private JsonNode permissions;

    @Convert(converter = JsonConverter.class)
    @Column(name = ModelConstants.ADDITIONAL_INFO_PROPERTY)
    private JsonNode additionalInfo;

    public RoleEntity() {
        super();
    }

    public RoleEntity(Role role) {
        super(role);
        if (role.getTenantId() != null) {
            this.tenantId = role.getTenantId().getId();
        }
        this.name = role.getName();
        this.type = role.getType();
        this.permissions = role.getPermissions();
        this.additionalInfo = role.getAdditionalInfo();
    }

    @Override
    public Role toData() {
        Role role = new Role(new RoleId(id));
        role.setCreatedTime(createdTime);
        if (tenantId != null) {
            role.setTenantId(TenantId.fromUUID(tenantId));
        }
        role.setName(name);
        role.setType(type);
        role.setPermissions(permissions);
        role.setAdditionalInfo(additionalInfo);
        role.setVersion(version);
        return role;
    }

}
