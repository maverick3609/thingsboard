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

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEventDescriptor;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerRepeat;
import org.thingsboard.server.common.data.scheduler.TimerRepeat;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.SchedulerEventInfoEntity;
import org.thingsboard.server.dao.model.sql.SchedulerEventWithCustomerInfoEntity;
import org.thingsboard.server.dao.scheduler.SchedulerEventInfoDao;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.util.SqlDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@SqlDao
@Slf4j
public class JpaSchedulerEventInfoDao extends JpaAbstractDao<SchedulerEventInfoEntity, SchedulerEventInfo> implements SchedulerEventInfoDao {

    @Autowired
    private SchedulerEventInfoRepository schedulerEventInfoRepository;

    @Override
    protected Class<SchedulerEventInfoEntity> getEntityClass() {
        return SchedulerEventInfoEntity.class;
    }

    @Override
    protected JpaRepository<SchedulerEventInfoEntity, UUID> getRepository() {
        return schedulerEventInfoRepository;
    }

    @Override
    public SchedulerEventWithCustomerInfo findSchedulerEventWithCustomerInfoById(UUID tenantId, UUID schedulerEventId) {
        log.debug("Try to find scheduler event with customer info by tenantId [{}] and schedulerEventId [{}]", tenantId, schedulerEventId);
        return DaoUtil.getData(schedulerEventInfoRepository.findSchedulerEventWithCustomerInfoById(schedulerEventId));
    }

    @Override
    public List<SchedulerEventInfo> findSchedulerEventsByTenantId(UUID tenantId) {
        log.debug("Try to find scheduler events by tenantId [{}]", tenantId);
        List<SchedulerEventInfo> result = DaoUtil.convertDataList(schedulerEventInfoRepository.findSchedulerEventInfoEntitiesByTenantId(tenantId));
        log.debug("Found [{}] scheduler events by tenantId [{}]", result.size(), tenantId);
        return result;
    }

    @Override
    public List<SchedulerEventInfo> findSchedulerEventsByTenantIdAndEnabled(UUID tenantId, boolean enabled) {
        log.debug("Try to find scheduler events by tenantId [{}] and enabled [{}]", tenantId, enabled);
        List<SchedulerEventInfo> result = DaoUtil.convertDataList(schedulerEventInfoRepository.findSchedulerEventInfoEntitiesByTenantIdAndEnabled(tenantId, enabled));
        log.debug("Found [{}] scheduler events by tenantId [{}] and enabled [{}]", result.size(), tenantId, enabled);
        return result;
    }

    @Override
    public PageData<SchedulerEventWithCustomerInfo> findSchedulerEventsByTenantIdAndFilter(UUID tenantId, SchedulerEventFilter filter, PageLink pageLink) {
        log.debug("Try to find scheduler events by tenantId [{}], filter [{}] and pageLink [{}]", tenantId, filter, pageLink);
        UUID customerId = filter.getCustomerId() != null && !filter.getCustomerId().isNullUid() ? filter.getCustomerId().getId() : null;
        String type = StringUtils.isNotBlank(filter.getType()) ? filter.getType() : null;
        PageData<SchedulerEventWithCustomerInfo> result = DaoUtil.toPageData(schedulerEventInfoRepository.findByTenantIdAndCustomerIdAndTypeAndSearchText(
                tenantId, customerId, type, Strings.emptyToNull(pageLink.getTextSearch()),
                DaoUtil.toPageable(pageLink, SchedulerEventWithCustomerInfoEntity.schedulerEventWithCustomerInfoColumnMap)));
        log.debug("Found [{}] scheduler events by tenantId [{}] and filter [{}]", result.getData().size(), tenantId, filter);
        return result;
    }

    @Override
    public List<SchedulerEventWithCustomerInfo> findAllSchedulerEventsByTenantIdAndEventTimeFilter(UUID tenantId, SchedulerEventTimeFilter filter, String searchText) {
        log.debug("Try to find scheduler events by tenantId [{}], time filter [{}] and searchText [{}]", tenantId, filter, searchText);
        List<SchedulerEventWithCustomerInfo> events = findSchedulerEventsByTenantIdAndFilter(tenantId, filter, new PageLink(Integer.MAX_VALUE, 0, searchText)).getData();
        long startTime = filter.getStartTime();
        long endTime = filter.getEndTime();
        List<SchedulerEventWithCustomerInfo> result = new ArrayList<>();
        for (SchedulerEventWithCustomerInfo event : events) {
            SchedulerEventDescriptor descriptor = event.toDescriptor();
            SchedulerRepeat schedulerRepeat = descriptor.repeat();
            if (schedulerRepeat instanceof TimerRepeat timerRepeat
                    && timerRepeat.getTimeUnit().toMillis(timerRepeat.getRepeatInterval()) < TimeUnit.DAYS.toMillis(1L)) {
                result.add(event);
                continue;
            }
            List<Long> timestamps = new ArrayList<>();
            long lastEventTime = startTime - 1L;
            long eventTime;
            while ((eventTime = descriptor.getNextEventTime(lastEventTime)) != 0L && eventTime <= endTime) {
                timestamps.add(eventTime);
                lastEventTime = eventTime;
            }
            if (timestamps.isEmpty()) {
                continue;
            }
            event.setTimestamps(timestamps);
            result.add(event);
        }
        log.debug("Found [{}] scheduler events by tenantId [{}] and time filter [{}]", result.size(), tenantId, filter);
        return result;
    }

    @Override
    public List<SchedulerEventId> findSchedulerEventsIdsByTenantIdAndCustomerId(UUID tenantId, UUID customerId) {
        log.debug("Try to find scheduler event ids by tenantId [{}] and customerId [{}]", tenantId, customerId);
        return schedulerEventInfoRepository.findIdsByTenantIdAndCustomerId(tenantId, customerId).stream().map(SchedulerEventId::new).toList();
    }

    @Override
    public List<SchedulerEventId> findSchedulerEventsIdsByTenantId(UUID tenantId) {
        log.debug("Try to find scheduler event ids by tenantId [{}]", tenantId);
        return schedulerEventInfoRepository.findIdsByTenantId(tenantId).stream().map(SchedulerEventId::new).toList();
    }

    @Override
    public ListenableFuture<List<SchedulerEventInfo>> findSchedulerEventsByTenantIdAndIdsAsync(UUID tenantId, List<UUID> schedulerEventIds) {
        log.debug("Try to find scheduler events by tenantId [{}] and ids [{}]", tenantId, schedulerEventIds);
        return service.submit(() -> DaoUtil.convertDataList(schedulerEventInfoRepository.findSchedulerEventsByTenantIdAndIdIn(tenantId, schedulerEventIds)));
    }

}
