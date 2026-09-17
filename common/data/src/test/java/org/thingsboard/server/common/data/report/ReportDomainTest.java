// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.id.ReportId;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ReportDomainTest {
    @Test void formatContentTypes() {
        assertThat(TbReportFormat.PDF.getContentType()).isEqualTo("application/pdf");
        assertThat(TbReportFormat.PDF.getExtension()).isEqualTo(".pdf");
        assertThat(TbReportFormat.CSV.getContentType()).isEqualTo("text/csv");
    }
    @Test void reportJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Report r = new Report(new ReportId(UUID.randomUUID()));
        r.setName("report-2026.pdf");
        r.setFormat(TbReportFormat.PDF);
        Report back = mapper.readValue(mapper.writeValueAsString(r), Report.class);
        assertThat(back.getName()).isEqualTo("report-2026.pdf");
        assertThat(back.getFormat()).isEqualTo(TbReportFormat.PDF);
    }
}
