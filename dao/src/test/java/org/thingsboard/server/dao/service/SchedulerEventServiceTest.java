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
package org.thingsboard.server.dao.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventTimeFilter;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.dao.scheduler.SchedulerEventService;
import org.thingsboard.server.exception.DataValidationException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@DaoSqlTest
public class SchedulerEventServiceTest extends AbstractServiceTest {

    @Autowired
    private SchedulerEventService schedulerEventService;

    @Test
    public void testSaveFindDeleteSchedulerEvent() {
        SchedulerEvent event = buildEvent("Nightly", "updateAttributes", null);
        SchedulerEvent saved = schedulerEventService.saveSchedulerEvent(event);
        assertNotNull(saved.getId());
        assertTrue(saved.getCreatedTime() > 0);
        assertEquals(tenantId, saved.getTenantId());

        SchedulerEvent found = schedulerEventService.findSchedulerEventById(tenantId, saved.getId());
        assertEquals(saved.getName(), found.getName());
        assertEquals(saved.getConfiguration(), found.getConfiguration());

        SchedulerEventInfo info = schedulerEventService.findSchedulerEventInfoById(tenantId, saved.getId());
        assertEquals(saved.getName(), info.getName());

        schedulerEventService.deleteSchedulerEvent(tenantId, saved.getId());
        assertNull(schedulerEventService.findSchedulerEventById(tenantId, saved.getId()));
    }

    @Test
    public void testFindByTenantIdAndEnabled_andWithCustomerInfo() {
        SchedulerEvent enabled = schedulerEventService.saveSchedulerEvent(buildEvent("On", "custom", null));
        SchedulerEvent disabled0 = buildEvent("Off", "custom", null);
        disabled0.setEnabled(false);
        schedulerEventService.saveSchedulerEvent(disabled0);

        List<SchedulerEventInfo> enabledEvents = schedulerEventService.findSchedulerEventsByTenantIdAndEnabled(tenantId, true);
        assertEquals(1, enabledEvents.size());
        assertEquals(enabled.getId(), enabledEvents.get(0).getId());

        // "with customer info" list is produced via the paged filter (PE has no dedicated by-tenant WithCustomerInfo finder)
        PageData<SchedulerEventWithCustomerInfo> all = schedulerEventService.findSchedulerEventsByTenantIdAndFilter(
                tenantId, SchedulerEventFilter.builder().build(), new PageLink(100, 0));
        assertEquals(2, all.getData().size());
    }

    @Test
    public void testPagedFilter_textSearch() {
        for (int i = 0; i < 5; i++) {
            schedulerEventService.saveSchedulerEvent(buildEvent("Filter event " + i, "custom", null));
        }
        schedulerEventService.saveSchedulerEvent(buildEvent("Other", "custom", null));
        PageData<SchedulerEventWithCustomerInfo> page = schedulerEventService.findSchedulerEventsByTenantIdAndFilter(
                tenantId, SchedulerEventFilter.builder().build(), new PageLink(3, 0, "filter event"));
        assertEquals(3, page.getData().size());
        assertTrue(page.hasNext());
        assertEquals(5, page.getTotalElements());
    }

    // NOTE (PE-verified behavior — see $PE_SRC JpaSchedulerEventInfoDao.findAllSchedulerEventsByTenantIdAndEventTimeFilter):
    //  - method is 3-arg: (tenantId, SchedulerEventTimeFilter, String searchText)
    //  - for a repeat with a sub-day TIMER interval (< 1 day) PE SHORT-CIRCUITS: the event is
    //    returned but timestamps stays NULL (a calendar wouldn't render minute-level ticks).
    //  - otherwise it walks getNextEventTime over [startTime, endTime]; events with zero
    //    occurrences in the window are DROPPED from the result.
    @Test
    public void testTimeFilter_computesTimestampsForDailyRepeat() {
        long start = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        long endsOn = start + TimeUnit.DAYS.toMillis(5);
        SchedulerEvent event = buildEvent("Daily", "custom",
                JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + start +
                        ",\"repeat\":{\"type\":\"DAILY\",\"endsOn\":" + endsOn + "}}"));
        schedulerEventService.saveSchedulerEvent(event);

        // window catches exactly the first (start) occurrence; next daily fire is start+24h, outside the window
        SchedulerEventTimeFilter filter = SchedulerEventTimeFilter.builder()
                .startTime(System.currentTimeMillis())
                .endTime(start + TimeUnit.MINUTES.toMillis(1))
                .build();
        List<SchedulerEventWithCustomerInfo> events =
                schedulerEventService.findAllSchedulerEventsByTenantIdAndEventTimeFilter(tenantId, filter, null);
        assertEquals(1, events.size());
        assertEquals(List.of(start), events.get(0).getTimestamps());

        // window entirely in the past (1970) — the daily event has zero occurrences there → dropped
        SchedulerEventTimeFilter past = SchedulerEventTimeFilter.builder()
                .startTime(1000L).endTime(2000L).build();
        assertTrue(schedulerEventService.findAllSchedulerEventsByTenantIdAndEventTimeFilter(tenantId, past, null).isEmpty());
    }

    @Test
    public void testTimeFilter_subDayTimer_returnedWithNullTimestamps() {
        long start = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        SchedulerEvent event = buildEvent("Hourly", "custom",
                timerSchedule(start, 1, "HOURS", start + TimeUnit.HOURS.toMillis(5)));
        schedulerEventService.saveSchedulerEvent(event);

        SchedulerEventTimeFilter filter = SchedulerEventTimeFilter.builder()
                .startTime(System.currentTimeMillis())
                .endTime(start + TimeUnit.HOURS.toMillis(3))
                .build();
        List<SchedulerEventWithCustomerInfo> events =
                schedulerEventService.findAllSchedulerEventsByTenantIdAndEventTimeFilter(tenantId, filter, null);
        // PE short-circuit: event present, timestamps NOT computed for sub-day timers
        assertEquals(1, events.size());
        assertNull(events.get(0).getTimestamps());
    }

    @Test
    public void testValidator_rejectsTooFrequentTimer() {
        SchedulerEvent event = buildEvent("Fast", "custom", timerSchedule(System.currentTimeMillis() + 10_000, 10, "SECONDS", System.currentTimeMillis() + 100_000));
        assertThrows(DataValidationException.class, () -> schedulerEventService.saveSchedulerEvent(event));
    }

    @Test
    public void testValidator_rejectsForeignCustomer() {
        SchedulerEvent event = buildEvent("Foreign", "custom", null);
        event.setCustomerId(new CustomerId(UUID.randomUUID()));
        assertThrows(DataValidationException.class, () -> schedulerEventService.saveSchedulerEvent(event));
    }

    // ---- helpers ----

    private SchedulerEvent buildEvent(String name, String type, JsonNode schedule) {
        SchedulerEvent event = new SchedulerEvent();
        event.setTenantId(tenantId);
        event.setName(name);
        event.setType(type);
        event.setSchedule(schedule != null ? schedule
                : JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + (System.currentTimeMillis() + 3600_000) + "}"));
        event.setConfiguration(JacksonUtil.toJsonNode("{\"originatorId\":null,\"msgType\":null,\"msgBody\":{},\"metadata\":null}"));
        return event;
    }

    private JsonNode timerSchedule(long start, int interval, String unit, long endsOn) {
        return JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + start +
                ",\"repeat\":{\"type\":\"TIMER\",\"repeatInterval\":" + interval + ",\"timeUnit\":\"" + unit + "\",\"endsOn\":" + endsOn + "}}");
    }

}
