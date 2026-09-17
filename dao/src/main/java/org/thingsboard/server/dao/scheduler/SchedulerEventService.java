// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.scheduler;

import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.dao.entity.EntityDaoService;

import java.util.List;

public interface SchedulerEventService extends EntityDaoService {

    SchedulerEvent findSchedulerEventById(TenantId tenantId, SchedulerEventId schedulerEventId);

    SchedulerEventInfo findSchedulerEventInfoById(TenantId tenantId, SchedulerEventId schedulerEventId);

    SchedulerEventWithCustomerInfo findSchedulerEventWithCustomerInfoById(TenantId tenantId, SchedulerEventId schedulerEventId);

    ListenableFuture<SchedulerEventInfo> findSchedulerEventInfoByIdAsync(TenantId tenantId, SchedulerEventId schedulerEventId);

    ListenableFuture<List<SchedulerEventInfo>> findSchedulerEventInfoByIdsAsync(TenantId tenantId, List<SchedulerEventId> schedulerEventIds);

    List<SchedulerEventInfo> findSchedulerEventsByTenantId(TenantId tenantId);

    List<SchedulerEventInfo> findSchedulerEventsByTenantIdAndEnabled(TenantId tenantId, boolean enabled);

    PageData<SchedulerEventWithCustomerInfo> findSchedulerEventsByTenantIdAndFilter(TenantId tenantId, SchedulerEventFilter filter, PageLink pageLink);

    List<SchedulerEventWithCustomerInfo> findAllSchedulerEventsByTenantIdAndEventTimeFilter(TenantId tenantId, SchedulerEventTimeFilter filter, String searchText);

    SchedulerEvent saveSchedulerEvent(SchedulerEvent schedulerEvent);

    void deleteSchedulerEvent(TenantId tenantId, SchedulerEventId schedulerEventId);

    void deleteSchedulerEventsByTenantId(TenantId tenantId);

    void deleteSchedulerEventsByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId);

}
