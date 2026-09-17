// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.dao.wl;

import lombok.Data;

@Data
public class WhiteLabelingEvictEvent {

    private final WhiteLabelingCacheKey key;

}
