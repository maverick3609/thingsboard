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
