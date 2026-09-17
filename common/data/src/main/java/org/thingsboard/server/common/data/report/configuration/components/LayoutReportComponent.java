// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.components;

import org.thingsboard.server.common.data.report.configuration.style.Insets;

public interface LayoutReportComponent extends ReportComponent {
    Insets getMargins();

    Insets getPaddings();

    String getBackground();

    Integer getBorderWidth();

    Integer getBorderRadius();

    String getBorderColor();
}
