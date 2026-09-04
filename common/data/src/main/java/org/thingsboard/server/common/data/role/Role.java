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
package org.thingsboard.server.common.data.role;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.thingsboard.server.common.data.BaseDataWithAdditionalInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.HasVersion;
import org.thingsboard.server.common.data.id.RoleId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.validation.Length;
import org.thingsboard.server.common.data.validation.NoXss;

/**
 * PE-compatible Role (RBAC port, Option B). GENERIC roles carry a permissions JSON of the
 * PE shape {@code {"DEVICE": ["READ", "WRITE"], "ALL": ["READ"]}} — resource name → operation
 * names. The permissions field follows the transient-JsonNode + byte[] pattern of
 * {@link BaseDataWithAdditionalInfo} so the entity stays serializable for caches and queue messages.
 */
@EqualsAndHashCode(callSuper = true, exclude = {"permissions"})
@ToString(exclude = {"permissionsBytes"})
public class Role extends BaseDataWithAdditionalInfo<RoleId> implements HasName, HasTenantId, HasVersion {

    private static final long serialVersionUID = 1L;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "JSON object with Tenant Id.", accessMode = Schema.AccessMode.READ_ONLY)
    @Getter
    @Setter
    private TenantId tenantId;

    @NoXss
    @Length(fieldName = "name")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Role name", example = "Read-Only")
    @Getter
    @Setter
    private String name;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Type of the role: GENERIC or GROUP", example = "GENERIC")
    @Getter
    @Setter
    private RoleType type;

    @Schema(description = "JSON object with the set of permissions: map of resource name to array of operation names")
    private transient JsonNode permissions;

    @JsonIgnore
    private byte[] permissionsBytes;

    @Getter
    @Setter
    private Long version;

    public Role() {
        super();
    }

    public Role(RoleId id) {
        super(id);
    }

    public Role(Role role) {
        super(role);
        this.tenantId = role.getTenantId();
        this.name = role.getName();
        this.type = role.getType();
        this.setPermissions(role.getPermissions());
        this.version = role.getVersion();
    }

    public JsonNode getPermissions() {
        return getJson(() -> permissions, () -> permissionsBytes);
    }

    public void setPermissions(JsonNode permissions) {
        setJson(permissions, json -> this.permissions = json, bytes -> this.permissionsBytes = bytes);
    }

    @JsonIgnore
    public EntityType getEntityType() {
        return EntityType.ROLE;
    }

}
