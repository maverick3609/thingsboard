/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
