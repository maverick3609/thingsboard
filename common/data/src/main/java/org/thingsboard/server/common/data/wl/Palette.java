// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.wl;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class Palette implements Serializable {
    private String type;
    private String extendsPalette;
    private Map<String, String> colors;
}
