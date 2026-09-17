// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.report.configuration.style;

import lombok.Getter;

@Getter
public enum PageSize {
    A4(595, 842),
    LETTER(612, 792),
    LEGAL(612, 1008),
    A5(420, 595),
    A3(842, 1191),
    TABLOID(792, 1224);

    private final int width;
    private final int height;

    PageSize(int pageWidth, int pageHeight) {
        this.width = pageWidth;
        this.height = pageHeight;
    }
}
