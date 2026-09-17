// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
