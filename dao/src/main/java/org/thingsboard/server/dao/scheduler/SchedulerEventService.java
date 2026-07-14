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
