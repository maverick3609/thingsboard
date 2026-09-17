// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.wl;

import lombok.Data;
import java.io.Serializable;

@Data
public class Favicon implements Serializable {
    private String url;
}
