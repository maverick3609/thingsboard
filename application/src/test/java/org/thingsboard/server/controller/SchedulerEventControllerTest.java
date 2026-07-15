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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerEventInfo;
import org.thingsboard.server.common.data.scheduler.SchedulerEventWithCustomerInfo;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class SchedulerEventControllerTest extends AbstractControllerTest {

    private Tenant savedTenant;
    private User tenantAdmin;

    @Before
    public void beforeTest() throws Exception {
        loginSysAdmin();

        Tenant tenant = new Tenant();
        tenant.setTitle("My tenant");
        savedTenant = saveTenant(tenant);
        Assert.assertNotNull(savedTenant);

        tenantAdmin = new User();
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setTenantId(savedTenant.getId());
        tenantAdmin.setEmail("tenant2@thingsboard.org");
        tenantAdmin.setFirstName("Joe");
        tenantAdmin.setLastName("Downs");

        tenantAdmin = createUserAndLogin(tenantAdmin, "testPassword1");
    }

    @After
    public void afterTest() throws Exception {
        loginSysAdmin();

        deleteTenant(savedTenant.getId());
    }

    @Test
    public void testSaveSchedulerEvent() throws Exception {
        Mockito.reset(tbClusterService, auditLogService);
        SchedulerEvent event = buildEvent("My schedule", "updateAttributes");

        SchedulerEvent saved = doPost("/api/schedulerEvent", event, SchedulerEvent.class);
        Assert.assertNotNull(saved.getId());
        Assert.assertTrue(saved.getCreatedTime() > 0);
        Assert.assertEquals(savedTenant.getId(), saved.getTenantId());
        Assert.assertEquals(event.getName(), saved.getName());
        // Scheduler save fires: audit log + ONE ENTITY_CREATED rule-engine push, but NO edge
        // notify (SCHEDULER_EVENT is not edge-syncable — edge sync was intentionally skipped).
        // logEntityActionService.logEntityAction does audit + pushEntityActionToRuleEngine
        // (ADDED->ENTITY_CREATED is present); it never calls sendNotificationMsgToEdge here.
        // Hence ...MsgToEdgeServiceNever, NOT ...AllOneTime (which asserts an edge notify fired).
        testNotifyEntityOneTimeMsgToEdgeServiceNever(saved, saved.getId(), saved.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.ADDED);

        Mockito.reset(tbClusterService, auditLogService);
        saved.setName("Renamed");
        SchedulerEvent updated = doPost("/api/schedulerEvent", saved, SchedulerEvent.class);
        testNotifyEntityOneTimeMsgToEdgeServiceNever(updated, updated.getId(), updated.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.UPDATED);

        SchedulerEvent found = doGet("/api/schedulerEvent/" + saved.getId().getId(), SchedulerEvent.class);
        Assert.assertEquals("Renamed", found.getName());
    }

    @Test
    public void testGetSchedulerEventInfo_withCustomerTitle() throws Exception {
        SchedulerEvent saved = doPost("/api/schedulerEvent", buildEvent("Info", "custom"), SchedulerEvent.class);
        SchedulerEventWithCustomerInfo info = doGet("/api/schedulerEvent/info/" + saved.getId().getId(), SchedulerEventWithCustomerInfo.class);
        Assert.assertEquals(saved.getId(), info.getId());
    }

    @Test
    public void testEnableDisable() throws Exception {
        SchedulerEvent saved = doPost("/api/schedulerEvent", buildEvent("Toggle", "custom"), SchedulerEvent.class);
        SchedulerEvent disabled = doPut("/api/schedulerEvent/" + saved.getId().getId() + "/enabled/false", "", SchedulerEvent.class);
        Assert.assertFalse(disabled.isEnabled());
        SchedulerEvent enabled = doPut("/api/schedulerEvent/" + saved.getId().getId() + "/enabled/true", "", SchedulerEvent.class);
        Assert.assertTrue(enabled.isEnabled());
    }

    @Test
    public void testDelete() throws Exception {
        SchedulerEvent saved = doPost("/api/schedulerEvent", buildEvent("Doomed", "custom"), SchedulerEvent.class);

        Mockito.reset(tbClusterService, auditLogService);
        doDelete("/api/schedulerEvent/" + saved.getId().getId()).andExpect(status().isOk());
        // Scheduler delete fires: audit log + ONE ENTITY_DELETED rule-engine push, but NO edge
        // notify (SCHEDULER_EVENT is not edge-syncable — mirrors the save-path exclusion).
        testNotifyEntityOneTimeMsgToEdgeServiceNever(saved, saved.getId(), saved.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.DELETED, saved.getId().getId());

        doGet("/api/schedulerEvent/" + saved.getId().getId()).andExpect(status().isNotFound());
    }

    @Test
    public void testListAndPagedAndByIds() throws Exception {
        for (int i = 0; i < 3; i++) {
            doPost("/api/schedulerEvent", buildEvent("Listed " + i, "custom"), SchedulerEvent.class);
        }
        List<SchedulerEventWithCustomerInfo> all = doGetTyped("/api/schedulerEvents",
                new TypeReference<List<SchedulerEventWithCustomerInfo>>() {});
        Assert.assertEquals(3, all.size());

        PageData<SchedulerEventWithCustomerInfo> page = doGetTypedWithPageLink("/api/schedulerEvents?",
                new TypeReference<PageData<SchedulerEventWithCustomerInfo>>() {}, new PageLink(2, 0));
        Assert.assertEquals(2, page.getData().size());
        Assert.assertTrue(page.hasNext());

        String ids = all.get(0).getId().getId() + "," + all.get(1).getId().getId();
        List<SchedulerEventInfo> byIds = doGetTyped("/api/schedulerEvents?schedulerEventIds=" + ids,
                new TypeReference<List<SchedulerEventInfo>>() {});
        Assert.assertEquals(2, byIds.size());
    }

    @Test
    public void testTimeWindowListing_returnsTimestamps() throws Exception {
        long start = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
        SchedulerEvent event = buildEvent("Windowed", "custom");
        event.setSchedule(JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + start + "}"));
        doPost("/api/schedulerEvent", event, SchedulerEvent.class);
        List<SchedulerEventWithCustomerInfo> hits = doGetTyped(
                "/api/schedulerEvents?startTime=" + System.currentTimeMillis() + "&endTime=" + (start + 1000),
                new TypeReference<List<SchedulerEventWithCustomerInfo>>() {});
        Assert.assertEquals(1, hits.size());
        Assert.assertEquals(List.of(start), hits.get(0).getTimestamps());
    }

    @Test
    public void testValidation_tooFrequentTimer_is400() throws Exception {
        SchedulerEvent event = buildEvent("TooFast", "custom");
        long now = System.currentTimeMillis();
        event.setSchedule(JacksonUtil.toJsonNode("{\"timezone\":\"UTC\",\"startTime\":" + (now + 10_000) +
                ",\"repeat\":{\"type\":\"TIMER\",\"repeatInterval\":10,\"timeUnit\":\"SECONDS\",\"endsOn\":" + (now + 100_000) + "}}"));
        doPost("/api/schedulerEvent", event)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("too frequent")));
    }

    @Test
    public void testCustomerUser_isScopedToOwnCustomer() throws Exception {
        SchedulerEvent tenantEvent = doPost("/api/schedulerEvent", buildEvent("TenantOnly", "custom"), SchedulerEvent.class);

        loginCustomerUser();
        SchedulerEvent customerEvent = doPost("/api/schedulerEvent", buildEvent("CustomerOwned", "custom"), SchedulerEvent.class);
        Assert.assertEquals(customerId, customerEvent.getCustomerId()); // forced to own customer

        List<SchedulerEventWithCustomerInfo> visible = doGetTyped("/api/schedulerEvents",
                new TypeReference<List<SchedulerEventWithCustomerInfo>>() {});
        Assert.assertEquals(1, visible.size());
        Assert.assertEquals(customerEvent.getId(), visible.get(0).getId());

        doGet("/api/schedulerEvent/" + tenantEvent.getId().getId()).andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    @Test
    public void testSysAdmin_hasNoAccess() throws Exception {
        SchedulerEvent saved = doPost("/api/schedulerEvent", buildEvent("NoSys", "custom"), SchedulerEvent.class);
        loginSysAdmin();
        doGet("/api/schedulerEvent/" + saved.getId().getId()).andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    private SchedulerEvent buildEvent(String name, String type) {
        SchedulerEvent event = new SchedulerEvent();
        event.setName(name);
        event.setType(type);
        event.setSchedule(JacksonUtil.toJsonNode(
                "{\"timezone\":\"UTC\",\"startTime\":" + (System.currentTimeMillis() + 3600_000) + "}"));
        event.setConfiguration(JacksonUtil.toJsonNode("{\"msgType\":null,\"msgBody\":{},\"metadata\":null}"));
        return event;
    }
}
