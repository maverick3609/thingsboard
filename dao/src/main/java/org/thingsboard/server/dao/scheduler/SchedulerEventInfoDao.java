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
