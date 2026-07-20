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
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class ReportTemplateControllerTest extends AbstractControllerTest {

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
    public void testSaveAndGetReportTemplate() throws Exception {
        Mockito.reset(tbClusterService, auditLogService);
        ReportTemplate template = buildTemplate("Weekly Summary");

        ReportTemplate saved = doPost("/api/reportTemplate", template, ReportTemplate.class);
        Assert.assertNotNull(saved.getId());
        Assert.assertTrue(saved.getCreatedTime() > 0);
        Assert.assertEquals(savedTenant.getId(), saved.getTenantId());
        Assert.assertEquals(template.getName(), saved.getName());
        // Unlike SCHEDULER_EVENT, REPORT_TEMPLATE is not excluded from EdgeEventSourcingListener's
        // default edge-sourcing path (isValidSaveEntityEventForEdgeProcessing falls through to
        // "true" for entity types it doesn't special-case), so save/delete DO fire exactly one
        // sendNotificationMsgToEdge call (with a null EdgeEventType, same as any other
        // not-yet-edge-mapped entity) in addition to the audit log + rule-engine push.
        testNotifyEntityAllOneTime(saved, saved.getId(), saved.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.ADDED);

        Mockito.reset(tbClusterService, auditLogService);
        saved.setName("Renamed");
        ReportTemplate updated = doPost("/api/reportTemplate", saved, ReportTemplate.class);
        testNotifyEntityAllOneTime(updated, updated.getId(), updated.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.UPDATED);

        ReportTemplate found = doGet("/api/reportTemplate/" + saved.getId().getId(), ReportTemplate.class);
        Assert.assertEquals("Renamed", found.getName());
        Assert.assertEquals(saved.getConfiguration(), found.getConfiguration());
    }

    @Test
    public void testGetReportTemplateInfoById() throws Exception {
        ReportTemplate saved = doPost("/api/reportTemplate", buildTemplate("Info"), ReportTemplate.class);
        ReportTemplateInfo info = doGet("/api/reportTemplate/info/" + saved.getId().getId(), ReportTemplateInfo.class);
        Assert.assertEquals(saved.getId(), info.getId());
        Assert.assertEquals(saved.getName(), info.getName());
    }

    @Test
    public void testDelete() throws Exception {
        ReportTemplate saved = doPost("/api/reportTemplate", buildTemplate("Doomed"), ReportTemplate.class);

        Mockito.reset(tbClusterService, auditLogService);
        doDelete("/api/reportTemplate/" + saved.getId().getId()).andExpect(status().isOk());
        testNotifyEntityAllOneTime(saved, saved.getId(), saved.getId(),
                savedTenant.getId(), tenantAdmin.getCustomerId(), tenantAdmin.getId(),
                tenantAdmin.getEmail(), ActionType.DELETED, saved.getId().getId());

        doGet("/api/reportTemplate/" + saved.getId().getId()).andExpect(status().isNotFound());
    }

    @Test
    public void testGetAllReportTemplateInfos() throws Exception {
        for (int i = 0; i < 3; i++) {
            doPost("/api/reportTemplate", buildTemplate("Listed " + i), ReportTemplate.class);
        }

        PageData<ReportTemplateInfo> page = doGetTypedWithPageLink("/api/reportTemplateInfos/all?",
                new TypeReference<PageData<ReportTemplateInfo>>() {}, new PageLink(10, 0));
        Assert.assertEquals(3, page.getData().size());

        PageData<ReportTemplateInfo> filtered = doGetTypedWithPageLink("/api/reportTemplateInfos/all?typeList=REPORT&",
                new TypeReference<PageData<ReportTemplateInfo>>() {}, new PageLink(10, 0));
        Assert.assertEquals(3, filtered.getData().size());
    }

    @Test
    public void testGetReportTemplatesByIds() throws Exception {
        ReportTemplate t1 = doPost("/api/reportTemplate", buildTemplate("One"), ReportTemplate.class);
        ReportTemplate t2 = doPost("/api/reportTemplate", buildTemplate("Two"), ReportTemplate.class);

        String ids = t1.getId().getId() + "," + t2.getId().getId();
        List<ReportTemplate> byIds = doGetTyped("/api/reportTemplates?reportTemplateIds=" + ids,
                new TypeReference<List<ReportTemplate>>() {});
        Assert.assertEquals(2, byIds.size());
    }

    @Test
    public void testCustomerUserCannotCreateTemplate() throws Exception {
        loginCustomerUser();
        doPost("/api/reportTemplate", buildTemplate("Nope"))
                .andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    @Test
    public void testCustomerUserCannotDeleteTemplate() throws Exception {
        ReportTemplate saved = doPost("/api/reportTemplate", buildTemplate("Protected"), ReportTemplate.class);

        loginCustomerUser();
        doDelete("/api/reportTemplate/" + saved.getId().getId())
                .andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    @Test
    public void testCustomerUserCannotReadTenantScopedTemplate() throws Exception {
        ReportTemplate saved = doPost("/api/reportTemplate", buildTemplate("TenantOnly"), ReportTemplate.class);

        loginCustomerUser();
        doGet("/api/reportTemplate/" + saved.getId().getId()).andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    @Test
    public void testSysAdmin_hasNoAccess() throws Exception {
        ReportTemplate saved = doPost("/api/reportTemplate", buildTemplate("NoSys"), ReportTemplate.class);
        loginSysAdmin();
        doGet("/api/reportTemplate/" + saved.getId().getId()).andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    private ReportTemplate buildTemplate(String name) {
        ReportTemplate t = new ReportTemplate();
        t.setName(name);
        t.setFormat(TbReportFormat.PDF);
        t.setType(ReportTemplateType.REPORT);
        t.setConfiguration(JacksonUtil.toJsonNode("{\"type\":\"PDF\",\"components\":[{\"type\":\"DASHBOARD\",\"dashboardId\":\"d1\"}]}"));
        return t;
    }
}
