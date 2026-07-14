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
package org.thingsboard.server.dao.sql.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.SchedulerEventEntity;
import org.thingsboard.server.dao.scheduler.SchedulerEventDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.Optional;
import java.util.UUID;

@Component
@SqlDao
@Slf4j
public class JpaSchedulerEventDao extends JpaAbstractDao<SchedulerEventEntity, SchedulerEvent> implements SchedulerEventDao {

    @Autowired
    private SchedulerEventRepository schedulerEventRepository;

    @Override
    protected Class<SchedulerEventEntity> getEntityClass() {
        return SchedulerEventEntity.class;
    }

    @Override
    protected JpaRepository<SchedulerEventEntity, UUID> getRepository() {
        return schedulerEventRepository;
    }

    @Override
    public PageData<SchedulerEvent> findByTenantId(UUID tenantId, PageLink pageLink) {
        log.debug("Try to find scheduler events by tenantId [{}] and pageLink [{}]", tenantId, pageLink);
        PageData<SchedulerEvent> result = DaoUtil.toPageData(schedulerEventRepository.findByTenantId(tenantId, DaoUtil.toPageable(pageLink)));
        log.debug("Found [{}] scheduler events by tenantId [{}]", result.getData().size(), tenantId);
        return result;
    }

    @Override
    public Long countByTenantId(TenantId tenantId) {
        return schedulerEventRepository.countByTenantId(tenantId.getId());
    }

    @Override
    public PageData<SchedulerEvent> findAllByTenantId(TenantId tenantId, PageLink pageLink) {
        return findByTenantId(tenantId.getId(), pageLink);
    }

    @Override
    public SchedulerEvent findByTenantIdAndExternalId(UUID tenantId, UUID externalId) {
        return DaoUtil.getData(schedulerEventRepository.findByTenantIdAndExternalId(tenantId, externalId));
    }

    @Override
    public SchedulerEventId getExternalIdByInternal(SchedulerEventId internalId) {
        return Optional.ofNullable(schedulerEventRepository.getExternalIdById(internalId.getId()))
                .map(SchedulerEventId::new).orElse(null);
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.SCHEDULER_EVENT;
    }

}
