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
