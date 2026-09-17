// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.EntityType;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPermissionsTest {

    @Test
    void resourceOfResolvesReportTypes() {
        assertThat(Resource.of(EntityType.REPORT)).isEqualTo(Resource.REPORT);
        assertThat(Resource.of(EntityType.REPORT_TEMPLATE)).isEqualTo(Resource.REPORT_TEMPLATE);
    }

}
