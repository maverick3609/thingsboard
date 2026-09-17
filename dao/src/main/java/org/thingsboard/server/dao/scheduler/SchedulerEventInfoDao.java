// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.scheduler;

import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.server.common.data.id.SchedulerEventId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.dao.Dao;

import java.util.List;
import java.util.UUID;

public interface SchedulerEventInfoDao extends Dao<SchedulerEventInfo> {

    SchedulerEventWithCustomerInfo findSchedulerEventWithCustomerInfoById(UUID tenantId, UUID schedulerEventId);

    List<SchedulerEventInfo> findSchedulerEventsByTenantId(UUID tenantId);

    List<SchedulerEventInfo> findSchedulerEventsByTenantIdAndEnabled(UUID tenantId, boolean enabled);

    PageData<SchedulerEventWithCustomerInfo> findSchedulerEventsByTenantIdAndFilter(UUID tenantId, SchedulerEventFilter filter, PageLink pageLink);

    List<SchedulerEventWithCustomerInfo> findAllSchedulerEventsByTenantIdAndEventTimeFilter(UUID tenantId, SchedulerEventTimeFilter filter, String searchText);

    List<SchedulerEventId> findSchedulerEventsIdsByTenantIdAndCustomerId(UUID tenantId, UUID customerId);

    List<SchedulerEventId> findSchedulerEventsIdsByTenantId(UUID tenantId);

    ListenableFuture<List<SchedulerEventInfo>> findSchedulerEventsByTenantIdAndIdsAsync(UUID tenantId, List<UUID> schedulerEventIds);

}
