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

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.id.ReportTemplateId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.report.ReportTemplate;
import org.thingsboard.server.common.data.report.ReportTemplateInfo;
import org.thingsboard.server.common.data.report.ReportTemplateQuery;
import org.thingsboard.server.common.data.report.ReportTemplateType;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.data.report.configuration.ReportTemplateConfig;
import org.thingsboard.server.common.data.report.configuration.components.DashboardComponent;
import org.thingsboard.server.common.data.report.configuration.components.ReportComponentType;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.service.AbstractServiceTest;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.exception.DataValidationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@DaoSqlTest
public class ReportTemplateServiceTest extends AbstractServiceTest {

    @Autowired
    private ReportTemplateService reportTemplateService;
    @Autowired
    private CustomerService customerService;

    @Test
    public void saveAndFindRoundTripsJsonbConfig() {
        ReportTemplate t = buildTemplate("Weekly", null);
        ReportTemplate saved = reportTemplateService.saveReportTemplate(t);
        assertNotNull(saved.getId());
        assertTrue(saved.getCreatedTime() > 0);

        ReportTemplate found = reportTemplateService.findReportTemplateById(tenantId, saved.getId());
        ReportTemplateConfig config = found.getConfiguration();
        // typed round-trip through the jsonb column: PDF root + nested DASHBOARD config + HEADING
        assertThat(config.getFormat()).isEqualTo(TbReportFormat.PDF);
        assertThat(config.getComponents()).hasSize(2);
        assertThat(config.getComponents().get(0).getType()).isEqualTo(ReportComponentType.DASHBOARD);
        assertThat(config.getComponents().get(1).getType()).isEqualTo(ReportComponentType.HEADING);
        DashboardComponent dashboard = (DashboardComponent) config.getComponents().get(0);
        assertThat(dashboard.getConfig()).isNotNull();
        assertThat(dashboard.getConfig().getDashboardId()).isEqualTo("d1");
        assertThat(dashboard.getConfig().getState()).isEqualTo("state-blob");
    }

    @Test
    public void uniqueExternalIdEnforced() {
        ReportTemplateId externalId = new ReportTemplateId(UUID.randomUUID());

        reportTemplateService.saveReportTemplate(buildTemplate("First", externalId));

        ReportTemplate second = buildTemplate("Second", externalId);
        assertThrows(DataValidationException.class, () -> reportTemplateService.saveReportTemplate(second));
    }

    @Test
    public void findInfosPagedByType() {
        for (int i = 0; i < 3; i++) {
            reportTemplateService.saveReportTemplate(buildTemplate("Report " + i, null));
        }

        ReportTemplateQuery query = ReportTemplateQuery.builder()
                .pageLink(new PageLink(10, 0))
                .typeList(List.of(ReportTemplateType.REPORT))
                .build();
        PageData<ReportTemplateInfo> page = reportTemplateService.findReportTemplateInfos(tenantId, query);
        assertEquals(3, page.getData().size());
    }

    @Test
    public void findReportTemplateInfoById_joinsCustomerTitle() {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setTitle("Report Customer");
        customer = customerService.saveCustomer(customer);

        ReportTemplate t = buildTemplate("Customer Report", null);
        t.setCustomerId(customer.getId());
        ReportTemplate saved = reportTemplateService.saveReportTemplate(t);

        ReportTemplateInfo info = reportTemplateService.findReportTemplateInfoById(tenantId, saved.getId());
        assertEquals("Report Customer", info.getCustomerTitle());
    }

    @Test
    public void findInfosIncludingCustomers_matchesCustomerTitleAndName() {
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setTitle("Zephyr Analytics Group");
        customer = customerService.saveCustomer(customer);

        ReportTemplate t = buildTemplate("Monthly Summary", null);
        t.setCustomerId(customer.getId());
        ReportTemplate saved = reportTemplateService.saveReportTemplate(t);

        // unrelated template: no customer, unrelated name - must not match either search below
        reportTemplateService.saveReportTemplate(buildTemplate("Unrelated Report", null));

        ReportTemplateQuery byCustomerTitle = ReportTemplateQuery.builder()
                .pageLink(new PageLink(10, 0, "Zephyr"))
                .includeCustomers(true)
                .build();
        PageData<ReportTemplateInfo> byCustomerTitleResult = reportTemplateService.findReportTemplateInfos(tenantId, byCustomerTitle);
        assertEquals(1, byCustomerTitleResult.getData().size());
        assertEquals(saved.getId(), byCustomerTitleResult.getData().get(0).getId());

        // regression: name-substring search must still work
        ReportTemplateQuery byName = ReportTemplateQuery.builder()
                .pageLink(new PageLink(10, 0, "Monthly"))
                .includeCustomers(true)
                .build();
        PageData<ReportTemplateInfo> byNameResult = reportTemplateService.findReportTemplateInfos(tenantId, byName);
        assertEquals(1, byNameResult.getData().size());
        assertEquals(saved.getId(), byNameResult.getData().get(0).getId());
    }

    @Test
    public void deleteRemovesReportTemplate() {
        ReportTemplate saved = reportTemplateService.saveReportTemplate(buildTemplate("ToDelete", null));
        reportTemplateService.deleteReportTemplate(tenantId, saved.getId());
        assertNull(reportTemplateService.findReportTemplateById(tenantId, saved.getId()));
    }

    // ---- helpers ----

    private ReportTemplate buildTemplate(String name, ReportTemplateId externalId) {
        ReportTemplate t = new ReportTemplate();
        t.setTenantId(tenantId);
        t.setName(name);
        t.setFormat(TbReportFormat.PDF);
        t.setType(ReportTemplateType.REPORT);
        t.setExternalId(externalId);
        // PE-shape (Decision D1): root keyed on `format`, DASHBOARD settings nested under `config`.
        t.setConfiguration(JacksonUtil.fromString(
                "{\"format\":\"PDF\",\"components\":[" +
                        "{\"type\":\"DASHBOARD\",\"config\":{\"dashboardId\":\"d1\",\"state\":\"state-blob\"}}," +
                        "{\"type\":\"HEADING\",\"value\":\"Weekly summary\"}]}",
                ReportTemplateConfig.class));
        return t;
    }

}
