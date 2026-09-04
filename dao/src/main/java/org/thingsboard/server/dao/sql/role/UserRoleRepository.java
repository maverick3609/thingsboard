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
package org.thingsboard.server.dao.sql.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.dao.model.sql.UserRoleCompositeKey;
import org.thingsboard.server.dao.model.sql.UserRoleEntity;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleCompositeKey> {

    List<UserRoleEntity> findAllByUserId(UUID userId);

    List<UserRoleEntity> findAllByRoleId(UUID roleId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserRoleEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);

}
