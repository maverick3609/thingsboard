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
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.dao.model.ModelConstants;

import java.util.UUID;

/**
 * Row of the user_role join table (RBAC Option B: direct user → role assignment).
 * Not a first-class entity — no BaseSqlEntity/domain mapping; the composite key IS the row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = ModelConstants.USER_ROLE_TABLE_NAME)
@IdClass(UserRoleCompositeKey.class)
public final class UserRoleEntity {

    @Id
    @Column(name = ModelConstants.USER_ROLE_USER_ID_PROPERTY)
    private UUID userId;

    @Id
    @Column(name = ModelConstants.USER_ROLE_ROLE_ID_PROPERTY)
    private UUID roleId;

    @Column(name = ModelConstants.TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Column(name = ModelConstants.CREATED_TIME_PROPERTY)
    private long createdTime;

}
