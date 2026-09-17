// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report;

public enum TbReportFormat {

    PDF("application/pdf", ".pdf"),
    CSV("text/csv", ".csv");

    private final String contentType;
    private final String extension;

    TbReportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    public String getExtension() {
        return extension;
    }

}
