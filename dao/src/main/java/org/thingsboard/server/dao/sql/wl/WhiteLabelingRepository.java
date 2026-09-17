// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.wl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;
import org.thingsboard.server.dao.model.sql.WhiteLabelingEntity;

import java.util.List;
import java.util.UUID;

public interface WhiteLabelingRepository extends JpaRepository<WhiteLabelingEntity, WhiteLabelingCompositeKey> {

    List<WhiteLabelingEntity> findByTenantId(UUID tenantId);

    void deleteByTenantId(UUID tenantId);
}
