// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.sql.wl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wl.WhiteLabeling;
import org.thingsboard.server.dao.model.sql.WhiteLabelingCompositeKey;
import org.thingsboard.server.dao.model.sql.WhiteLabelingEntity;
import org.thingsboard.server.dao.sql.JpaAbstractDaoListeningExecutorService;
import org.thingsboard.server.dao.util.SqlDao;
import org.thingsboard.server.dao.wl.WhiteLabelingDao;

@Component
@SqlDao
@RequiredArgsConstructor
public class JpaWhiteLabelingDao extends JpaAbstractDaoListeningExecutorService implements WhiteLabelingDao {

    private final WhiteLabelingRepository repository;

    @Override
    public WhiteLabeling save(WhiteLabeling whiteLabeling) {
        WhiteLabelingEntity entity = new WhiteLabelingEntity(whiteLabeling);
        return repository.save(entity).toData();
    }

    @Override
    public WhiteLabeling findByCompositeKey(WhiteLabelingCompositeKey key) {
        return repository.findById(key).map(WhiteLabelingEntity::toData).orElse(null);
    }

    @Override
    @Transactional
    public boolean removeByCompositeKey(WhiteLabelingCompositeKey key) {
        if (repository.existsById(key)) {
            repository.deleteById(key);
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public void deleteByTenantId(TenantId tenantId) {
        repository.deleteByTenantId(tenantId.getId());
    }
}
