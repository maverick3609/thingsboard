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
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.ReportInfo;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.dao.report.ReportService;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Task 20: {@code /api/v2/report*} history + create endpoints. The renderer stays OFF
 * (property {@code reports.renderer.enabled} is not set) for this whole class - the point is to
 * prove the controller boots and the history endpoints work with {@code Optional<TbReportService>}
 * empty, not to exercise rendering. {@code /report/test} and {@code /report/request} are therefore
 * NOT covered here (they need the renderer / full job execution); they're covered by
 * {@code TbReportServiceTest} (Task 14) and {@code InferrixSchedulerReportExecutorTest} (Task 16)
 * at the service layer, and are context-boot-verified here only via the {@code Optional} injection
 * succeeding for every other test in this class.
 */
@DaoSqlTest
public class ReportControllerTest extends AbstractControllerTest {

    @Autowired
    private ReportService reportService;

    @Test
    public void testListAndDownloadReport() throws Exception {
        loginTenantAdmin();
        Report r = createReportViaDao("r.pdf", "%PDF-x".getBytes());

        PageData<ReportInfo> page = doGetTyped("/api/v2/reportInfos?pageSize=10&page=0", new TypeReference<>() {});
        assertThat(page.getData()).extracting(ReportInfo::getName).contains("r.pdf");

        MvcResult res = doGet("/api/v2/report/" + r.getId() + "/download").andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getHeader("x-filename")).isEqualTo("r.pdf");
        assertThat(res.getResponse().getHeader("Content-Disposition")).contains("attachment;filename=\"r.pdf\"");
        assertThat(res.getResponse().getContentAsByteArray()).startsWith("%PDF-".getBytes());
    }

    @Test
    public void testDeleteReport() throws Exception {
        loginTenantAdmin();
        Report r = createReportViaDao("doomed.pdf", "%PDF-x".getBytes());

        doDelete("/api/v2/report/" + r.getId()).andExpect(status().isOk());

        doGet("/api/v2/report/" + r.getId()).andExpect(status().isNotFound());
    }

    @Test
    public void testCustomerUserCannotDeleteReport() throws Exception {
        loginTenantAdmin();
        Report r = createReportViaDao("protected.pdf", "%PDF-x".getBytes());

        loginCustomerUser();
        doDelete("/api/v2/report/" + r.getId()).andExpect(status().isForbidden());
        loginTenantAdmin();
    }

    /**
     * Not in the brief's minimum coverage list, added because {@code createReport} deliberately
     * deviates from PE (see {@code ReportController} javadoc): it force-sets tenantId/customerId
     * from the session instead of trusting the multipart {@code info} part, and that fix is
     * otherwise unverified by this suite.
     */
    @Test
    public void testCreateReport() throws Exception {
        loginTenantAdmin();
        Report info = new Report();
        info.setName("created.pdf");
        info.setFormat(TbReportFormat.PDF);
        info.setUserId(tenantAdminUserId);

        MockMultipartFile file = new MockMultipartFile("file", "created.pdf", "application/pdf", "%PDF-y".getBytes());
        var request = MockMvcRequestBuilders.multipart("/api/v2/report")
                .file(file)
                .part(new MockPart("info", JacksonUtil.toString(info).getBytes(StandardCharsets.UTF_8)));
        setJwtToken(request);
        Report saved = readResponse(mockMvc.perform(request).andExpect(status().isOk()), Report.class);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);

        Report found = doGet("/api/v2/report/" + saved.getId(), Report.class);
        assertThat(found.getName()).isEqualTo("created.pdf");
    }

    private Report createReportViaDao(String name, byte[] data) {
        Report report = new Report();
        report.setTenantId(tenantId);
        report.setUserId(tenantAdminUserId);
        report.setFormat(TbReportFormat.PDF);
        report.setName(name);
        return reportService.createReport(report, data);
    }

}
