// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema
public class PageBreakComponent implements ReportComponent {

    @Override
    public ReportComponentType getType() {
        return ReportComponentType.PAGE_BREAK;
    }
}
