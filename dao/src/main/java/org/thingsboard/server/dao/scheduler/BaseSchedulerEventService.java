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
package org.thingsboard.server.dao.scheduler;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.entity.EntityCountService;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.Validator;

import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

@Service("SchedulerEventDaoService")
@Slf4j
public class BaseSchedulerEventService extends AbstractEntityService implements SchedulerEventService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_SCHEDULER_EVENT_ID = "Incorrect schedulerEventId ";

    @Autowired
    private SchedulerEventDao schedulerEventDao;
    @Autowired
    private SchedulerEventInfoDao schedulerEventInfoDao;
    @Autowired
    private DataValidator<SchedulerEvent> schedulerEventValidator;
    @Autowired
    private EntityCountService entityCountService;

    @Override
    public SchedulerEvent findSchedulerEventById(TenantId tenantId, SchedulerEventId schedulerEventId) {
        log.debug("Executing findSchedulerEventById, tenantId [{}], schedulerEventId [{}]", tenantId, schedulerEventId);
        Validator.validateId(schedulerEventId, id -> INCORRECT_SCHEDULER_EVENT_ID + id);
        return schedulerEventDao.findById(tenantId, schedulerEventId.getId());
    }

    @Override
    public SchedulerEventInfo findSchedulerEventInfoById(TenantId tenantId, SchedulerEventId schedulerEventId) {
        log.debug("Executing findSchedulerEventInfoById, tenantId [{}], schedulerEventId [{}]", tenantId, schedulerEventId);
        Validator.validateId(schedulerEventId, id -> INCORRECT_SCHEDULER_EVENT_ID + id);
        return schedulerEventInfoDao.findById(tenantId, schedulerEventId.getId());
    }

    @Override
    public SchedulerEventWithCustomerInfo findSchedulerEventWithCustomerInfoById(TenantId tenantId, SchedulerEventId schedulerEventId) {
        log.debug("Executing findSchedulerEventWithCustomerInfoById, tenantId [{}], schedulerEventId [{}]", tenantId, schedulerEventId);
        Validator.validateId(schedulerEventId, id -> INCORRECT_SCHEDULER_EVENT_ID + id);
        return schedulerEventInfoDao.findSchedulerEventWithCustomerInfoById(tenantId.getId(), schedulerEventId.getId());
    }

    @Override
    public ListenableFuture<SchedulerEventInfo> findSchedulerEventInfoByIdAsync(TenantId tenantId, SchedulerEventId schedulerEventId) {
        log.debug("Executing findSchedulerEventInfoByIdAsync, tenantId [{}], schedulerEventId [{}]", tenantId, schedulerEventId);
        Validator.validateId(schedulerEventId, id -> INCORRECT_SCHEDULER_EVENT_ID + id);
        return schedulerEventInfoDao.findByIdAsync(tenantId, schedulerEventId.getId());
    }

    @Override
    public ListenableFuture<List<SchedulerEventInfo>> findSchedulerEventInfoByIdsAsync(TenantId tenantId, List<SchedulerEventId> schedulerEventIds) {
        log.debug("Executing findSchedulerEventInfoByIdsAsync, tenantId [{}], schedulerEventIds [{}]", tenantId, schedulerEventIds);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validateIds(schedulerEventIds, ids -> "Incorrect schedulerEventIds " + ids);
        return schedulerEventInfoDao.findSchedulerEventsByTenantIdAndIdsAsync(tenantId.getId(), DaoUtil.toUUIDs(schedulerEventIds));
    }

    @Override
    public List<SchedulerEventInfo> findSchedulerEventsByTenantId(TenantId tenantId) {
        log.debug("Executing findSchedulerEventsByTenantId, tenantId [{}]", tenantId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        List<SchedulerEventInfo> result = schedulerEventInfoDao.findSchedulerEventsByTenantId(tenantId.getId());
        log.debug("Found [{}] scheduler events, tenantId [{}]", result.size(), tenantId);
        return result;
    }

    @Override
    public List<SchedulerEventInfo> findSchedulerEventsByTenantIdAndEnabled(TenantId tenantId, boolean enabled) {
        log.debug("Executing findSchedulerEventsByTenantIdAndEnabled, tenantId [{}], enabled [{}]", tenantId, enabled);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        List<SchedulerEventInfo> result = schedulerEventInfoDao.findSchedulerEventsByTenantIdAndEnabled(tenantId.getId(), enabled);
        log.debug("Found [{}] scheduler events, tenantId [{}], enabled [{}]", result.size(), tenantId, enabled);
        return result;
    }

    @Override
    public PageData<SchedulerEventWithCustomerInfo> findSchedulerEventsByTenantIdAndFilter(TenantId tenantId, SchedulerEventFilter filter, PageLink pageLink) {
        log.debug("Executing findSchedulerEventsByTenantIdAndFilter, tenantId [{}], filter [{}], pageLink [{}]", tenantId, filter, pageLink);
        PageData<SchedulerEventWithCustomerInfo> result = schedulerEventInfoDao.findSchedulerEventsByTenantIdAndFilter(tenantId.getId(), filter, pageLink);
        log.debug("Found [{}] scheduler events, tenantId [{}], filter [{}]", result.getData().size(), tenantId, filter);
        return result;
    }

    @Override
    public List<SchedulerEventWithCustomerInfo> findAllSchedulerEventsByTenantIdAndEventTimeFilter(TenantId tenantId, SchedulerEventTimeFilter filter, String searchText) {
        log.debug("Executing findAllSchedulerEventsByTenantIdAndEventTimeFilter, tenantId [{}], filter [{}], searchText [{}]", tenantId, filter, searchText);
        List<SchedulerEventWithCustomerInfo> result = schedulerEventInfoDao.findAllSchedulerEventsByTenantIdAndEventTimeFilter(tenantId.getId(), filter, searchText);
        log.debug("Found [{}] scheduler events, tenantId [{}], filter [{}]", result.size(), tenantId, filter);
        return result;
    }

    @Override
    public SchedulerEvent saveSchedulerEvent(SchedulerEvent schedulerEvent) {
        log.debug("Executing saveSchedulerEvent, tenantId [{}], name [{}]", schedulerEvent.getTenantId(), schedulerEvent.getName());
        schedulerEventValidator.validate(schedulerEvent, SchedulerEventInfo::getTenantId);
        try {
            SchedulerEvent savedSchedulerEvent = schedulerEventDao.save(schedulerEvent.getTenantId(), schedulerEvent);
            if (schedulerEvent.getId() == null) {
                entityCountService.publishCountEntityEvictEvent(schedulerEvent.getTenantId(), EntityType.SCHEDULER_EVENT);
            }
            eventPublisher.publishEvent(SaveEntityEvent.builder().tenantId(schedulerEvent.getTenantId()).entityId(savedSchedulerEvent.getId())
                    .entity(savedSchedulerEvent).created(schedulerEvent.getId() == null).build());
            log.info("Saved scheduler event, tenantId [{}], id [{}], name [{}]", savedSchedulerEvent.getTenantId(), savedSchedulerEvent.getId(), savedSchedulerEvent.getName());
            return savedSchedulerEvent;
        } catch (Exception e) {
            log.warn("Failed to save scheduler event, tenantId [{}], name [{}]", schedulerEvent.getTenantId(), schedulerEvent.getName());
            checkConstraintViolation(e, "scheduler_event_external_id_unq_key", "SchedulerEvent with such external id already exists!");
            throw e;
        }
    }

    @Override
    public void deleteSchedulerEvent(TenantId tenantId, SchedulerEventId schedulerEventId) {
        log.debug("Executing deleteSchedulerEvent, tenantId [{}], schedulerEventId [{}]", tenantId, schedulerEventId);
        Validator.validateId(schedulerEventId, id -> INCORRECT_SCHEDULER_EVENT_ID + id);
        SchedulerEvent schedulerEvent = findSchedulerEventById(tenantId, schedulerEventId);
        if (schedulerEvent == null) {
            return;
        }
        schedulerEventDao.removeById(tenantId, schedulerEventId.getId());
        entityCountService.publishCountEntityEvictEvent(tenantId, EntityType.SCHEDULER_EVENT);
        eventPublisher.publishEvent(DeleteEntityEvent.builder().tenantId(tenantId).entityId(schedulerEventId).entity(schedulerEvent).build());
        log.info("Deleted scheduler event, tenantId [{}], id [{}], name [{}]", tenantId, schedulerEventId, schedulerEvent.getName());
    }

    @Override
    public void deleteEntity(TenantId tenantId, EntityId id, boolean force) {
        deleteSchedulerEvent(tenantId, (SchedulerEventId) id);
    }

    @Override
    public void deleteSchedulerEventsByTenantId(TenantId tenantId) {
        log.debug("Executing deleteSchedulerEventsByTenantId, tenantId [{}]", tenantId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        List<SchedulerEventId> schedulerEvents = schedulerEventInfoDao.findSchedulerEventsIdsByTenantId(tenantId.getId());
        for (SchedulerEventId id : schedulerEvents) {
            deleteSchedulerEvent(tenantId, id);
        }
    }

    @Override
    public void deleteByTenantId(TenantId tenantId) {
        deleteSchedulerEventsByTenantId(tenantId);
    }

    @Override
    public void deleteSchedulerEventsByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId) {
        log.debug("Executing deleteSchedulerEventsByTenantIdAndCustomerId, tenantId [{}], customerId [{}]", tenantId, customerId);
        Validator.validateId(tenantId, id -> INCORRECT_TENANT_ID + id);
        Validator.validateId(customerId, id -> INCORRECT_CUSTOMER_ID + id);
        List<SchedulerEventId> schedulerEvents = schedulerEventInfoDao.findSchedulerEventsIdsByTenantIdAndCustomerId(tenantId.getId(), customerId.getId());
        for (SchedulerEventId id : schedulerEvents) {
            deleteSchedulerEvent(tenantId, id);
        }
    }

    public static EntityId getOriginatorId(SchedulerEvent event) {
        return event.getOriginatorId() != null ? event.getOriginatorId() : event.getId();
    }

    @Override
    public Optional<HasId<?>> findEntity(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(findSchedulerEventById(tenantId, new SchedulerEventId(entityId.getId())));
    }

    @Override
    public FluentFuture<Optional<HasId<?>>> findEntityAsync(TenantId tenantId, EntityId entityId) {
        return FluentFuture.from(schedulerEventDao.findByIdAsync(tenantId, entityId.getId()))
                .transform(Optional::ofNullable, directExecutor());
    }

    @Override
    public long countByTenantId(TenantId tenantId) {
        return schedulerEventDao.countByTenantId(tenantId);
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.SCHEDULER_EVENT;
    }

}
