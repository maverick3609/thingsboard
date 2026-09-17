// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.report.render;

/**
 * Thrown when {@link PlaywrightWebReportRenderer} fails to produce a render: a browser/page-level
 * error, or a hard timeout that force-closed the in-flight {@code BrowserContext}.
 */
public class ReportRenderException extends RuntimeException {

    public ReportRenderException(String message) {
        super(message);
    }

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }

}
