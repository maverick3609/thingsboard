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
package org.thingsboard.server.dao.report;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.ReportInfoQuery;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.service.AbstractServiceTest;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.exception.DataValidationException;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

@DaoSqlTest
public class ReportServiceTest extends AbstractServiceTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private UserService userService;

    private UserId userId;

    @Before
    public void before() {
        User tenantAdmin = new User();
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setTenantId(tenantId);
        tenantAdmin.setEmail("report-tester@thingsboard.org");
        User user = userService.saveUser(TenantId.SYS_TENANT_ID, tenantAdmin);
        userId = user.getId();
    }

    @After
    public void after() {
        reportService.deleteReportsByTenantId(tenantId);
        User user = userService.findUserById(tenantId, userId);
        userService.deleteUser(tenantId, user);
    }

    @Test
    public void createStoresBytesRetrievableSeparately() {
        byte[] bytes = "PDFDATA".getBytes(StandardCharsets.UTF_8);
        Report saved = reportService.createReport(buildReport("r.pdf"), bytes);

        assertNotNull(saved.getId());
        assertThat(reportService.getReportData(tenantId, saved.getId())).isEqualTo(bytes);
        assertThat(reportService.findReportById(tenantId, saved.getId()).getName()).isEqualTo("r.pdf");
    }

    @Test
    public void createStoresMultiKilobytePayload() {
        byte[] bytes = new byte[64 * 1024];
        new Random(42).nextBytes(bytes);
        Report saved = reportService.createReport(buildReport("big.pdf"), bytes);

        assertThat(reportService.getReportData(tenantId, saved.getId())).isEqualTo(bytes);
    }

    @Test
    public void reportsAreImmutable() {
        Report saved = reportService.createReport(buildReport("immutable.pdf"), "v1".getBytes(StandardCharsets.UTF_8));

        assertThrows(DataValidationException.class,
                () -> reportService.createReport(saved, "v2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void findReportsPagedAndTextSearch() {
        for (int i = 0; i < 3; i++) {
            reportService.createReport(buildReport("Report " + i), "data".getBytes(StandardCharsets.UTF_8));
        }

        PageData<ReportInfo> page = reportService.findReports(tenantId,
                ReportInfoQuery.builder().pageLink(new PageLink(10, 0)).build());
        assertEquals(3, page.getData().size());
    }

    @Test
    public void findReportsIncludingCustomers_matchesCustomerTitleAndName() {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setTitle("Zephyr Analytics Group");
        customer = customerService.saveCustomer(customer);

        Report withCustomer = buildReport("Monthly Summary");
        withCustomer.setCustomerId(customer.getId());
        Report saved = reportService.createReport(withCustomer, "data".getBytes(StandardCharsets.UTF_8));

        // unrelated report: no customer, unrelated name - must not match either search below
        reportService.createReport(buildReport("Unrelated Report"), "data".getBytes(StandardCharsets.UTF_8));

        PageData<ReportInfo> byCustomerTitle = reportService.findReports(tenantId,
                ReportInfoQuery.builder().pageLink(new PageLink(10, 0, "Zephyr")).includeCustomers(true).build());
        assertEquals(1, byCustomerTitle.getData().size());
        assertEquals(saved.getId(), byCustomerTitle.getData().get(0).getId());

        // regression: name-substring search must still work
        PageData<ReportInfo> byName = reportService.findReports(tenantId,
                ReportInfoQuery.builder().pageLink(new PageLink(10, 0, "Monthly")).includeCustomers(true).build());
        assertEquals(1, byName.getData().size());
        assertEquals(saved.getId(), byName.getData().get(0).getId());
    }

    @Test
    public void findReportInfoById_joinsCustomerTitle() {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setTitle("Report Customer");
        customer = customerService.saveCustomer(customer);

        Report r = buildReport("Customer Report");
        r.setCustomerId(customer.getId());
        Report saved = reportService.createReport(r, "data".getBytes(StandardCharsets.UTF_8));

        ReportInfo info = reportService.findReportInfoById(tenantId, saved.getId());
        assertEquals("Report Customer", info.getCustomerTitle());
    }

    @Test
    public void deleteRemovesRowAndData() {
        Report saved = reportService.createReport(buildReport("ToDelete"), "data".getBytes(StandardCharsets.UTF_8));

        reportService.deleteReport(tenantId, saved.getId());

        assertNull(reportService.findReportById(tenantId, saved.getId()));
        assertThat(reportService.getReportData(tenantId, saved.getId())).isNullOrEmpty();
    }

    // ---- helpers ----

    private Report buildReport(String name) {
        Report r = new Report();
        r.setTenantId(tenantId);
        r.setUserId(userId);
        r.setName(name);
        r.setFormat(TbReportFormat.PDF);
        return r;
    }

}
