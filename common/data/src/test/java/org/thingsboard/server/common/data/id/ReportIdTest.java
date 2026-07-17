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
package org.thingsboard.server.common.data.id;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.EntityType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportIdTest {
    @Test void reportIdCarriesReportType() {
        UUID u = UUID.fromString("784f394c-42b6-435a-983c-b7beff2784f9");
        assertThat(new ReportId(u).getEntityType()).isEqualTo(EntityType.REPORT);
        assertThat(new ReportTemplateId(u).getEntityType()).isEqualTo(EntityType.REPORT_TEMPLATE);
    }
    @Test void entityTypeProtoNumbersMatchPe() {
        assertThat(EntityType.REPORT_TEMPLATE.getProtoNumber()).isEqualTo(108);
        assertThat(EntityType.REPORT.getProtoNumber()).isEqualTo(109);
    }
    @Test void factoryResolvesBothTypes() {
        UUID u = UUID.randomUUID();
        assertThat(EntityIdFactory.getByTypeAndUuid("REPORT", u)).isInstanceOf(ReportId.class);
        assertThat(EntityIdFactory.getByTypeAndUuid("REPORT_TEMPLATE", u)).isInstanceOf(ReportTemplateId.class);
    }
}
